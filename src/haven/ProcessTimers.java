package haven;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H4D: finish timers for smelters, ovens, kilns, crucibles and the like (Ender/Kami style).
 *
 * The server only reports progress (the items' meters) while the object's window is open, so the
 * timer is computed when you look inside and then counts down on its own after you close it.
 * The full processing time of an item is learned by watching its meter tick twice; learned times
 * are saved per (object, item) so the next look gives a timer straight away. Ore smelting and the
 * finery forge start from known times. Smelters and stack furnaces pause their timer while unlit.
 */
public class ProcessTimers {
    private static final String[] PROCESSORS = {
	"gfx/terobjs/smelter", "gfx/terobjs/primsmelter", "gfx/terobjs/fineryforge",
	"gfx/terobjs/oven", "gfx/terobjs/kiln", "gfx/terobjs/steelcrucible", "gfx/terobjs/crucible",
	"gfx/terobjs/smokeshed", "gfx/terobjs/tarkiln",
    };
    /** Seconds a whole item takes, when known up front. */
    private static final Map<String, Double> SEED = new HashMap<>();
    static {
	SEED.put("gfx/terobjs/smelter", 55 * 60.0);
	SEED.put("gfx/terobjs/primsmelter", 55 * 60.0);
	SEED.put("gfx/terobjs/fineryforge", 9 * 60.0);
    }

    public static class Entry {
	public final String gobres;
	public volatile double nextEnd = -1, allEnd = -1; // Utils.rtime() seconds; -1 = unknown
	public volatile double pct = -1;                 // highest meter shown when the time is unknown
	public volatile boolean paused = false;
	Entry(String gobres) {this.gobres = gobres;}
    }

    private static class Bound {
	final Window wnd; final long gobid; final String gobres;
	Bound(Window wnd, long gobid, String gobres) {this.wnd = wnd; this.gobid = gobid; this.gobres = gobres;}
    }

    private static class ItemTrack {
	double last = -1;    // last meter value seen
	double changed = -1; // when it last changed (rtime), -1 = never seen change
	double rate = -1;    // this item's own learned seconds-per-whole
    }

    private static final Map<Long, Entry> timers = new ConcurrentHashMap<>();
    private static final List<Bound> bound = new ArrayList<>();
    private static final Map<GItem, ItemTrack> tracks = new WeakHashMap<>();
    private static final Map<String, Double> learned = new HashMap<>();

    private static Gob lastGob = null;
    private static double lastGobTime = 0;

    public static boolean isProcessor(String resname) {
	if(resname == null) return false;
	for(String p : PROCESSORS)
	    if(resname.equals(p)) return true;
	return false;
    }

    public static boolean enabled() {
	return OptWnd.showProcessTimersCheckBox == null || OptWnd.showProcessTimersCheckBox.a;
    }

    public static Entry get(long gobid) {return timers.get(gobid);}

    public static void noteInteract(Gob gob) {
	lastGob = gob;
	lastGobTime = Utils.rtime();
    }

    public static void windowOpened(GameUI gui, Window wnd, long objid) {
	Gob gob = null;
	if(objid >= 0 && gui.map != null)
	    gob = gui.map.glob.oc.getgob(objid);
	if(gob == null && lastGob != null && Utils.rtime() - lastGobTime < 5)
	    gob = lastGob;
	if(gob == null) return;
	String res = resname(gob);
	if(!isProcessor(res)) return;
	lastGob = null;
	synchronized(bound) {
	    bound.removeIf(b -> b.wnd == wnd);
	    bound.add(new Bound(wnd, gob.id, res));
	}
    }

    public static void tick(GameUI gui, double dt) {
	double now = Utils.rtime();
	synchronized(bound) {
	    for(Iterator<Bound> i = bound.iterator(); i.hasNext();) {
		Bound b = i.next();
		if(b.wnd.parent == null) {i.remove(); continue;}
		try {
		    update(b, now);
		} catch(Loading ignored) {
		} catch(Exception e) {
		    i.remove();
		}
	    }
	}
	/* Unlit smelters and stack furnaces make no progress: push their timers back while they are cold. */
	if(gui.map == null) return;
	for(Map.Entry<Long, Entry> e : timers.entrySet()) {
	    Entry t = e.getValue();
	    Boolean lit = null;
	    Gob gob = gui.map.glob.oc.getgob(e.getKey());
	    if(gob != null) {
		try {lit = lit(gob, t.gobres);} catch(Exception ignored) {}
	    }
	    t.paused = (lit != null) && !lit;
	    if(t.paused) {
		if(t.nextEnd > now) t.nextEnd += dt;
		if(t.allEnd > now) t.allEnd += dt;
	    }
	    /* Forget timers that finished more than an hour ago. */
	    if(t.allEnd > 0 && now - t.allEnd > 3600)
		timers.remove(e.getKey());
	}
    }

    private static void update(Bound b, double now) {
	double next = Double.MAX_VALUE, all = -1, pct = -1;
	boolean any = false, unknown = false;
	for(WItem w : b.wnd.children(WItem.class)) {
	    double m = w.meter();
	    String ires = w.item.getres().name;
	    ItemTrack t;
	    synchronized(tracks) {t = tracks.computeIfAbsent(w.item, k -> new ItemTrack());}
	    if(t.last >= 0 && m != t.last) {
		if(t.changed >= 0 && m > t.last && m - t.last <= 0.05) {
		    double whole = (now - t.changed) / (m - t.last);
		    t.rate = whole;
		    learn(b.gobres, ires, whole);
		}
		t.changed = now;
	    }
	    t.last = m;
	    if(m <= 0 || m >= 1) continue;
	    any = true;
	    double whole = (t.rate > 0) ? t.rate : rate(b.gobres, ires);
	    if(whole <= 0) {
		unknown = true;
		pct = Math.max(pct, m);
		continue;
	    }
	    double rem = whole * (1 - m);
	    if(t.changed >= 0) rem -= (now - t.changed);
	    double end = now + Math.max(rem, 0);
	    next = Math.min(next, end);
	    all = Math.max(all, end);
	}
	if(!any) {
	    timers.remove(b.gobid);
	    return;
	}
	Entry e = timers.computeIfAbsent(b.gobid, k -> new Entry(b.gobres));
	if(unknown && all < 0) {
	    e.nextEnd = -1; e.allEnd = -1; e.pct = pct;
	} else {
	    e.nextEnd = next; e.allEnd = all; e.pct = -1;
	}
    }

    private static String key(String gobres, String ires) {return("proctime/" + gobres + "/" + ires);}

    private static synchronized double rate(String gobres, String ires) {
	String k = key(gobres, ires);
	Double v = learned.get(k);
	if(v == null) {
	    try {v = Double.parseDouble(Utils.getpref(k, "-1"));} catch(NumberFormatException e) {v = -1.0;}
	    learned.put(k, v);
	}
	if(v > 0) return v;
	return SEED.getOrDefault(gobres, -1.0);
    }

    private static synchronized void learn(String gobres, String ires, double whole) {
	if(whole < 1 || whole > 14 * 24 * 3600) return;
	String k = key(gobres, ires);
	Double old = learned.get(k);
	double v = (old != null && old > 0) ? (old + whole) / 2 : whole;
	learned.put(k, v);
	Utils.setpref(k, String.valueOf(Math.round(v)));
    }

    /** null when the object's lit state isn't known from its appearance. */
    private static Boolean lit(Gob gob, String res) {
	if(res == null) return null;
	int sdt;
	Message m = gob.sdtm();
	if(m.eom()) return null;
	sdt = m.uint8();
	if(res.equals("gfx/terobjs/smelter")) return (sdt & 0b0010) != 0;
	if(res.equals("gfx/terobjs/primsmelter")) return (sdt & 0b0001) != 0;
	return null;
    }

    private static String resname(Gob gob) {
	try {
	    Resource r = gob.getres();
	    return (r == null) ? null : r.name;
	} catch(Loading e) {
	    return null;
	}
    }

    public static String fmt(double secs) {
	long s = Math.max(0, Math.round(secs));
	if(s >= 3600) return String.format("%dh %02dm", s / 3600, (s % 3600) / 60);
	if(s >= 60) return String.format("%dm %02ds", s / 60, s % 60);
	return String.format("%ds", s);
    }
}
