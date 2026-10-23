package haven.automated;

import haven.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Auto Kin: shares this character's hearth secret with the Web Map server (same token as map
 * uploads) and replays every other group member's secret as "Add kin by Hearth Secret".
 * <p>
 * One instance per {@link BuddyWnd}. Everything that touches the kin list runs on the UI thread
 * from {@link #tick()}; HTTP runs on a single background thread and hands results back through
 * volatile fields / queues. Ported from Nurgling2's NBuddyWnd kin-secret sharing, with its
 * database swapped for two Web Map endpoints:
 * <ul>
 *   <li>{@code POST {webMapEndpoint}/kinSecret} body {@code {genus, charName, secret}} (empty secret = delete)</li>
 *   <li>{@code GET {webMapEndpoint}/kinSecrets?genus=...} returns {@code [{charName, secret, updatedAt}]}</li>
 * </ul>
 * The server only accepts Web Map tenant admins; anyone else gets HTTP 403.
 * See doc/H4D-features/auto-kin.md.
 */
public class AutoKin {
    public static final String PREF_SHARE = "autoKinShareSecret";
    public static final String PREF_GENERATE = "autoKinGenerateSecret";
    public static final String PREF_ON_LOGIN = "autoKinOnLogin";
    /** Kin group (index into {@link BuddyWnd#gc}) for kin from the Web Map group, or {@link #GROUP_KEEP}. */
    public static final String PREF_GROUP = "autoKinGroup";
    /** Replaced by {@link #PREF_GROUP}; only read once to carry an old choice over. */
    private static final String PREF_GREEN_OLD = "autoKinGreenGroup";
    /** {@link #PREF_GROUP} value: leave kin in whatever group the game put them. */
    public static final int GROUP_KEEP = -1;
    /** The group new kin arrive in. */
    private static final int DEFAULT_GROUP = 0;
    private static final int GREEN_GROUP = 1;
    /** Groups offered in the options: the seven colours after the default white one. */
    public static final int[] GROUP_CHOICES = {GROUP_KEEP, 1, 2, 3, 4, 5, 6, 7};
    private static final String[] GROUP_NAMES = {"White", "Green", "Red", "Blue", "Cyan", "Yellow", "Purple", "Orange"};
    /** Seconds between two "bypwd" messages while a sync drains. */
    private static final double SEND_INTERVAL = 0.25;
    /** How long after the last "bypwd" an incoming "add" is still attributed to the sync. */
    private static final double SEND_GRACE = 10.0;
    /** Seconds between attempts to publish a secret that has not reached the server yet. */
    private static final double PUBLISH_RETRY = 30.0;
    /** How long to wait for the game server to state this character's secret before assuming there is none. */
    private static final double AUTOGEN_GRACE = 10.0;
    /** Delay after login before the automatic sync, so the kin list has finished arriving. */
    private static final double LOGIN_SYNC_DELAY = 15.0;
    /** Minimum seconds between two name-list fetches caused by an unknown new kin. */
    private static final double POOL_REFETCH = 10.0;
    /** Name-list fetches for one batch of unknown new kin before giving up on them. */
    private static final int POOL_REFETCH_TRIES = 3;
    private static final int TIMEOUT_MS = 10000;
    private static final String PWCHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    /** A hearth secret is a credential: worth a real CSPRNG rather than Math.random(). */
    private static final SecureRandom PWRNG = new SecureRandom();
    private static final ExecutorService http = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AutoKin");
        t.setDaemon(true);
        return t;
    });

    private final BuddyWnd wnd;
    private final double created = Utils.rtime();

    /* Own secret */
    private boolean sawPwd = false;
    private boolean haveSecret = false;
    private boolean autogenDone = false;
    /** Secret still to reach the server. Empty string means "delete my row". */
    private volatile String pendingPublish = null;
    private volatile String lastPublished = null;
    private volatile boolean publishing = false;
    private double lastPublishTry = -PUBLISH_RETRY;

    /* Sync */
    private boolean loginSyncDone = false;
    private volatile boolean syncLoading = false;
    private volatile List<String[]> syncRows = null;
    private volatile boolean syncForce = false;
    private boolean syncRunning = false;
    private final ConcurrentLinkedQueue<String[]> sendQueue = new ConcurrentLinkedQueue<>();
    private int syncTotal = 0, syncSent = 0, syncAdded = 0;
    private double lastSend = 0;
    /** Character names of the Web Map group from the last kinSecrets fetch, this character excluded. */
    private volatile Set<String> poolNames = Collections.emptySet();
    /** New kin not in {@link #poolNames}, re-checked once the names are fetched again. */
    private final List<BuddyWnd.Buddy> unknownAdds = new ArrayList<>();
    /** Set by the HTTP thread when a fetch started by {@link #tickUnknownAdds} has finished. */
    private volatile boolean refreshDone = false;
    private int refreshTries = 0;
    private volatile boolean poolLoading = false;
    private double lastPoolFetch = -POOL_REFETCH;

    /** Latest status line; the window shows it under the sync button. */
    private volatile String status = "";

    public AutoKin(BuddyWnd wnd) {
        this.wnd = wnd;
    }

    public String status() {
        return status;
    }

    public static boolean sharing() {
        return Utils.getprefb(PREF_SHARE, false);
    }

    /** The chosen kin group, or {@link #GROUP_KEEP}. Defaults to green, or to the old checkbox's choice. */
    public static int group() {
        int def = Utils.getprefb(PREF_GREEN_OLD, true) ? GREEN_GROUP : GROUP_KEEP;
        int grp = Utils.getprefi(PREF_GROUP, def);
        return ((grp == GROUP_KEEP) || ((grp > DEFAULT_GROUP) && (grp < GROUP_NAMES.length))) ? grp : def;
    }

    public static void setGroup(int grp) {
        Utils.setprefi(PREF_GROUP, grp);
    }

    public static String groupName(int grp) {
        if (grp == GROUP_KEEP)
            return "Don't change";
        return ((grp >= 0) && (grp < GROUP_NAMES.length)) ? GROUP_NAMES[grp] : ("Group " + grp);
    }

    // ------------------------------------------------------------------ hooks from BuddyWnd

    /** The game server stated this character's current hearth secret ("pwd" message). */
    public void onServerSecret(String secret) {
        if (secret == null)
            secret = "";
        sawPwd = true;
        if (!secret.isEmpty())
            haveSecret = true;
        queuePublish(secret);
    }

    /** The player (or Auto Kin itself) set or cleared the secret through {@link BuddyWnd#setpwd}. */
    public void onSetSecret(String secret) {
        if (secret == null)
            secret = "";
        if (secret.isEmpty()) {
            /* Clearing holds for this session, so the Clear button visibly works instead of
             * being undone right away. Turning generation off for good is the option's job. */
            autogenDone = true;
            haveSecret = false;
        } else {
            haveSecret = true;
        }
        queuePublish(secret);
    }

    /**
     * A kin was added ("add" message). Any add while a sync drains is taken to be ours. Kin whose
     * name is in the Web Map group are moved to the chosen group at any time, so a group member
     * whose own sync kinned us while we were logged in ends up there too.
     */
    public void onKinAdded(BuddyWnd.Buddy b) {
        if (b == null)
            return;
        boolean ours = syncActive();
        if (ours)
            syncAdded++;
        if (!sharing())
            return;
        int grp = group();
        if ((grp == GROUP_KEEP) || (b.group == grp))
            return;
        if (ours || poolNames.contains(b.name)) {
            move(b, grp);
        } else if (endpoint() != null) {
            /* Maybe a group member who joined after our last fetch: ask the server again. */
            System.out.println("[AutoKin] new kin '" + b.name + "' is not in the Web Map group list yet, refreshing it");
            unknownAdds.add(b);
            refreshTries = 0;
        }
    }

    /** "Sync kin from Web Map" button. Shift-click resends every secret, current kin included. */
    public void startSync(boolean force) {
        if (syncLoading || syncRunning)
            return;
        if (!sharing()) {
            setStatus("Turn on Auto Kin in Server Integration settings");
            return;
        }
        String base = endpoint();
        GameUI gui = gui();
        if (base == null) {
            setStatus("Set your Web Map Endpoint first");
            return;
        }
        if (gui == null || empty(gui.genus) || empty(gui.chrid)) {
            setStatus("Not logged in to a world yet");
            return;
        }
        final String genus = gui.genus, mine = gui.chrid;
        syncLoading = true;
        syncForce = force;
        setStatus("Loading kin from Web Map...");
        http.execute(() -> {
            try {
                List<String[]> rows = fetchRows(base, genus, mine);
                if (rows != null)
                    syncRows = rows;
            } catch (Exception e) {
                setStatus("Web Map unreachable");
                System.out.println("[AutoKin] sync failed: " + e);
            } finally {
                syncLoading = false;
            }
        });
    }

    /**
     * GET kinSecrets on the HTTP thread and refresh {@link #poolNames}. Returns the
     * {name, secret} rows of everyone but this character, or null (status set) on an HTTP error.
     */
    private List<String[]> fetchRows(String base, String genus, String mine) throws Exception {
        Response r = request("GET", base + "/kinSecrets?genus=" + URLEncoder.encode(genus, "UTF-8"), null);
        if (r.code != 200) {
            setStatus(failText(r.code));
            return null;
        }
        JSONArray arr = new JSONArray(r.body);
        List<String[]> rows = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String name = o.optString("charName", "");
            String secret = o.optString("secret", "");
            if (name.isEmpty() || secret.isEmpty() || name.equals(mine))
                continue;
            rows.add(new String[] {name, secret});
            names.add(name);
        }
        poolNames = Collections.unmodifiableSet(names);
        return rows;
    }

    /**
     * Move every kin from the Web Map group to the chosen group now, e.g. after the option changed.
     * Uses the names from the last fetch, or runs a sync (which regroups) if there are none yet.
     */
    public void regroup() {
        if (!sharing() || (group() == GROUP_KEEP))
            return;
        Set<String> pool = poolNames;
        if (pool.isEmpty()) {
            startSync(false);
            return;
        }
        int grp = group();
        for (BuddyWnd.Buddy b : wnd) {
            if ((b.group != grp) && pool.contains(b.name))
                move(b, grp);
        }
    }

    /**
     * Re-check kin that arrived outside a sync against a name list fetched after they arrived.
     * A group member's secret can reach the server a moment after their client kinned us, so a
     * name still missing is looked up again a few times before giving up.
     */
    private void tickUnknownAdds(double now) {
        if (unknownAdds.isEmpty())
            return;
        if (refreshDone) {
            refreshDone = false;
            int grp = group();
            Set<String> pool = poolNames;
            for (Iterator<BuddyWnd.Buddy> i = unknownAdds.iterator(); i.hasNext();) {
                BuddyWnd.Buddy b = i.next();
                if ((grp == GROUP_KEEP) || (b.group == grp)) {
                    i.remove();
                } else if (pool.contains(b.name)) {
                    move(b, grp);
                    i.remove();
                }
            }
            if (!unknownAdds.isEmpty() && (refreshTries >= POOL_REFETCH_TRIES)) {
                for (BuddyWnd.Buddy b : unknownAdds)
                    System.out.println("[AutoKin] kin '" + b.name + "' is not in the Web Map group list, leaving their group as is");
                unknownAdds.clear();
            }
            if (unknownAdds.isEmpty())
                refreshTries = 0;
            return;
        }
        if (poolLoading || ((now - lastPoolFetch) < POOL_REFETCH))
            return;
        String base = endpoint();
        GameUI gui = gui();
        if (!sharing() || base == null || gui == null || empty(gui.genus) || empty(gui.chrid)) {
            unknownAdds.clear();
            refreshTries = 0;
            return;
        }
        lastPoolFetch = now;
        refreshTries++;
        poolLoading = true;
        final String genus = gui.genus, mine = gui.chrid;
        http.execute(() -> {
            try {
                fetchRows(base, genus, mine);
            } catch (Exception e) {
                System.out.println("[AutoKin] kin list refresh failed: " + e);
            } finally {
                poolLoading = false;
                refreshDone = true;
            }
        });
    }

    /** Called every UI tick from {@link BuddyWnd#tick}, visible or not. */
    public void tick() {
        double now = Utils.rtime();
        tickAutogen(now);
        tickPublish(now);
        tickLoginSync(now);
        tickSync(now);
        tickUnknownAdds(now);
    }

    // ------------------------------------------------------------------ own secret

    private void queuePublish(String secret) {
        if (secret.equals(lastPublished))
            return;
        pendingPublish = secret;
        lastPublishTry = -PUBLISH_RETRY;
    }

    private void tickPublish(double now) {
        String secret = pendingPublish;
        if (secret == null || publishing || !sharing())
            return;
        if ((now - lastPublishTry) < PUBLISH_RETRY)
            return;
        String base = endpoint();
        GameUI gui = gui();
        if (base == null || gui == null || empty(gui.genus) || empty(gui.chrid))
            return;
        lastPublishTry = now;
        publishing = true;
        final String genus = gui.genus, mine = gui.chrid;
        http.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("genus", genus);
                body.put("charName", mine);
                body.put("secret", secret);
                Response r = request("POST", base + "/kinSecret", body.toString());
                if (r.code == 200 || r.code == 204) {
                    lastPublished = secret;
                    /* A newer secret may have been queued while this one was in flight. */
                    if (secret.equals(pendingPublish))
                        pendingPublish = null;
                } else {
                    if (r.code == 403 || r.code == 404) {
                        /* Not an admin / server without Auto Kin: retrying will not help this session. */
                        pendingPublish = null;
                    }
                    setStatus(failText(r.code));
                }
            } catch (Exception e) {
                System.out.println("[AutoKin] publish failed: " + e);
            } finally {
                publishing = false;
            }
        });
    }

    /**
     * Give this character a hearth secret if it has none, so the rest of the group can kin it.
     * Only when sharing is on: there is no reason to change the character otherwise.
     */
    private void tickAutogen(double now) {
        if (autogenDone || haveSecret)
            return;
        if (!sawPwd && ((now - created) < AUTOGEN_GRACE))
            return;
        if (!sharing() || !Utils.getprefb(PREF_GENERATE, true) || endpoint() == null)
            return;
        GameUI gui = gui();
        if (gui == null || empty(gui.chrid))
            return;
        autogenDone = true;
        wnd.setpwd(randomSecret());
        gui.msg("Auto Kin: this character had no hearth secret, so one was generated.", Color.YELLOW);
    }

    private static String randomSecret() {
        StringBuilder buf = new StringBuilder(8);
        for (int i = 0; i < 8; i++)
            buf.append(PWCHARS.charAt(PWRNG.nextInt(PWCHARS.length())));
        return buf.toString();
    }

    // ------------------------------------------------------------------ sync

    private void tickLoginSync(double now) {
        if (loginSyncDone || (now - created) < LOGIN_SYNC_DELAY)
            return;
        loginSyncDone = true;
        if (sharing() && Utils.getprefb(PREF_ON_LOGIN, true) && endpoint() != null)
            startSync(false);
    }

    private boolean syncActive() {
        return syncRunning && (lastSend > 0) && ((Utils.rtime() - lastSend) < SEND_GRACE);
    }

    /**
     * Turn the server's rows into a send queue, on the UI thread because it reads the kin list.
     * Anyone already in the list by name is skipped. Kin shown under a nickname do not match and
     * get their secret re-sent, which the game server ignores for existing kin.
     * <p>
     * Existing kin from the Web Map group who are not in the chosen group are moved to it.
     */
    private void prepareSync() {
        List<String[]> rows = syncRows;
        if (rows == null)
            return;
        syncRows = null;
        Set<String> kin = new HashSet<>();
        int grp = group();
        Set<String> pool = poolNames;
        for (BuddyWnd.Buddy b : wnd) {
            kin.add(b.name);
            if ((grp != GROUP_KEEP) && (b.group != grp) && pool.contains(b.name))
                move(b, grp);
        }
        sendQueue.clear();
        for (String[] r : rows) {
            if (!syncForce && kin.contains(r[0]))
                continue;
            sendQueue.add(r);
        }
        syncTotal = sendQueue.size();
        syncSent = 0;
        syncAdded = 0;
        lastSend = 0;
        syncRunning = true;
        if (syncTotal > 0)
            setStatus("Adding kin 0/" + syncTotal);
    }

    private void tickSync(double now) {
        prepareSync();
        if (!syncRunning)
            return;
        if (!sendQueue.isEmpty() && ((now - lastSend) >= SEND_INTERVAL)) {
            String[] entry = sendQueue.poll();
            if (entry != null) {
                lastSend = now;
                syncSent++;
                wnd.wdgmsg("bypwd", entry[1]);
                setStatus("Adding kin " + syncSent + "/" + syncTotal);
            }
        }
        /* "bypwd" is never acknowledged, so wait out the grace period for the last adds. */
        if (sendQueue.isEmpty() && ((syncSent == 0) || ((now - lastSend) > SEND_GRACE))) {
            syncRunning = false;
            if (syncAdded > 0) {
                setStatus("Added " + syncAdded + " kin");
                GameUI gui = gui();
                if (gui != null)
                    gui.msg("Auto Kin: added " + syncAdded + " kin from your Web Map group.", BuddyWnd.gcolor((group() == GROUP_KEEP) ? GREEN_GROUP : group()));
            } else {
                setStatus("Kin up to date");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void move(BuddyWnd.Buddy b, int grp) {
        System.out.println("[AutoKin] moving kin '" + b.name + "' from group " + b.group + " to " + grp);
        b.chgrp(grp);
    }

    private GameUI gui() {
        return wnd.getparent(GameUI.class);
    }

    /** The Web Map endpoint ({@code https://host/client/<token>}) without a trailing slash, or null. */
    private static String endpoint() {
        String raw = Utils.getpref("webMapEndpoint", "");
        if (raw == null)
            return null;
        raw = raw.trim();
        while (raw.endsWith("/"))
            raw = raw.substring(0, raw.length() - 1);
        return (raw.length() < 5) ? null : raw;
    }

    private static boolean empty(String s) {
        return (s == null) || s.isEmpty();
    }

    private void setStatus(String text) {
        status = (text == null) ? "" : text;
    }

    private static String failText(int code) {
        if (code == 401 || code == 403)
            return "Auto Kin needs Web Map admin";
        if (code == 404)
            return "Web Map server has no Auto Kin";
        return "Web Map error " + code;
    }

    private static class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static Response request(String method, String url, String json) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "H&H Client");
            if (json != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = conn.getResponseCode();
            String body = "";
            if (code == 200) {
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    in.transferTo(buf);
                    body = buf.toString(StandardCharsets.UTF_8);
                }
            }
            return new Response(code, body);
        } finally {
            conn.disconnect();
        }
    }
}
