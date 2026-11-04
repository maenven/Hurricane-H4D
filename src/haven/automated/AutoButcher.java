package haven.automated;

import haven.*;
import haven.render.Pipe;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * H4D: Auto Butcher (Other Scripts & Tools). A small window where you pick a stat (milk/meat/wool amount or one
 * of the qualities, as shown by Animal Stats Above Heads), a threshold and which genders count, then drag a
 * rectangle over a pen. Every memorized (Cattle Roster) animal in it whose stat is below the threshold is
 * marked red; pressing Start slaughters them one by one, nearest first, and (optionally) butchers each carcass
 * where it fell: Skin, Clean, Butcher, Collect bones, the same order as Nurgling2's Butcher bot.
 *
 * Stats come from the roster entries (AnimalStatsOverlay.value), so animals not in the roster are never touched.
 * See doc/H4D-features/auto-butcher.md.
 */
public class AutoButcher extends Window {
    public static final String PREF_STAT = "autoButcherStat";
    public static final String PREF_LIMIT = "autoButcherLimit";
    public static final String PREF_GENDER = "autoButcherGender";
    public static final String PREF_SKIP_YOUNG = "autoButcherSkipYoung";
    public static final String PREF_CARCASS = "autoButcherCarcass";
    public static final String PREF_ROSTER_RM = "autoButcherRosterRemove";

    /** Roster entry fields: male flag and young flag per species (Ochs, Goat, Sheep, Pig, Horse, Teimdeer). */
    private static final String[] MALE_FIELDS = {"bull", "billy", "ram", "hog", "stallion", "buck"};
    private static final String[] YOUNG_FIELDS = {"calf", "kid", "lamb", "piglet", "foal", "fawn"};

    /** Carcass flower-menu options, in the order they have to be done, with the free space each needs. */
    private static final String[] CARCASS_ORDER = {"Skin", "Scale", "Crack", "Clean", "Butcher", "Collect bones"};
    private static final Map<String, int[]> CARCASS_SPACE = new HashMap<>();
    static {
	// {width, height, how many of them} - Nurgling2's numbers
	CARCASS_SPACE.put("Skin", new int[]{2, 2, 1});
	CARCASS_SPACE.put("Scale", new int[]{1, 1, 3});
	CARCASS_SPACE.put("Crack", new int[]{2, 2, 1});
	CARCASS_SPACE.put("Clean", new int[]{1, 1, 1});
	CARCASS_SPACE.put("Butcher", new int[]{1, 1, 2});
	CARCASS_SPACE.put("Collect bones", new int[]{2, 2, 1});
    }

    private static final int SLAUGHTER_TIMEOUT = 90000;
    private static final int STEP_TIMEOUT = 120000;

    public enum Gender {
	BOTH("Males and females"), MALE("Males only"), FEMALE("Females only");
	public final String label;
	Gender(String label) {this.label = label;}
    }

    private static AutoButcher instance = null;

    private final GameUI gui;
    private final StatBox statBox;
    private final TextEntry limitEntry;
    private final GenderBox genderBox;
    private final CheckBox youngBox, carcassBox, rosterBox;
    private final Label info;
    private final Button areaBtn, startBtn;

    private Coord2d ul, br;
    /** Animals found by the last area selection that will be slaughtered (gob id -> label). */
    private volatile Map<Long, String> targets = Collections.emptyMap();
    private volatile Thread worker = null;
    private final Map<Gob, Boolean> marked = new HashMap<>();
    /** Text for the info label, set by the bot thread and shown on the next UI tick. */
    private volatile String pendingInfo = null;
    private boolean shownRunning = false;

    /** The menu-grid button: opens the window, or brings it back / closes it. */
    public static void menuButton(GameUI gui) {
	if (gui == null || gui.map == null)
	    return;
	if (instance != null && instance.gui == gui && instance.parent != null) {
	    instance.close();
	    return;
	}
	instance = new AutoButcher(gui);
	gui.add(instance, Utils.getprefc("wndc-autoButcherWindow", new Coord(gui.sz.x / 2 - instance.sz.x / 2, gui.sz.y / 2 - instance.sz.y / 2 - 150)));
    }

    private AutoButcher(GameUI gui) {
	super(UI.scale(300, 200), "Auto Butcher");
	this.gui = gui;
	int x = UI.scale(5), lw = UI.scale(95), fw = UI.scale(190);
	int y = UI.scale(5), row = UI.scale(24);

	add(new Label("Stat:"), x, y + UI.scale(2));
	statBox = add(new StatBox(fw), x + lw, y);
	y += row;
	add(new Label("Butcher below:"), x, y + UI.scale(2));
	limitEntry = add(new TextEntry(UI.scale(60), Utils.getpref(PREF_LIMIT, "30")) {
		protected void changed() {
		    super.changed();
		    if (ul != null && parseLimit() != null && !running())
			preview();
		}
	    }, x + lw, y);
	y += row;
	add(new Label("Genders:"), x, y + UI.scale(2));
	genderBox = add(new GenderBox(fw), x + lw, y);
	y += row;
	youngBox = add(new PrefBox("Skip young animals", PREF_SKIP_YOUNG, true,
	    "Leave calves, lambs, kids, piglets, foals and fawns alone."), x, y);
	y += UI.scale(20);
	carcassBox = add(new PrefBox("Butcher the carcasses", PREF_CARCASS, true,
	    "After slaughtering, Skin, Clean, Butcher and Collect bones on the carcass where it fell.\nOff: only slaughter, leave the carcasses."), x, y);
	y += UI.scale(20);
	rosterBox = add(new PrefBox("Remove them from the Cattle Roster", PREF_ROSTER_RM, true,
	    "Remove each slaughtered animal from the Cattle Roster, like the roster's \"Remove selected\" button."), x, y);
	y += UI.scale(24);
	areaBtn = add(new Button(UI.scale(140), "Select area", false).action(this::selectArea), x, y);
	startBtn = add(new Button(UI.scale(140), "Start", false).action(this::startOrStop), x + UI.scale(150), y);
	y += UI.scale(30);
	info = add(new Label("Pick the settings, then Select area over the pen."), x, y);
	resize(contentsz().add(UI.scale(10), UI.scale(5)));
    }

    // ---------------------------------------------------------------------------------------
    // UI thread
    // ---------------------------------------------------------------------------------------

    private void setInfo(String s) {
	info.settext(s);
	resize(contentsz().add(UI.scale(10), UI.scale(5)));
    }

    private void selectArea() {
	if (running()) {
	    gui.error("Auto Butcher is running; stop it first.");
	    return;
	}
	if (parseLimit() == null) {
	    gui.error("Auto Butcher: type a number in \"Butcher below\".");
	    return;
	}
	boolean started = gui.map.selectAreaClientSide((a, b) -> {
	    ul = new Coord2d(a.x * MCache.tilesz.x, a.y * MCache.tilesz.y);
	    br = new Coord2d(b.x * MCache.tilesz.x, b.y * MCache.tilesz.y);
	    preview();
	});
	if (started)
	    gui.msg("Auto Butcher: drag a rectangle over the animals. Right-click cancels.", Color.WHITE);
	else
	    gui.error("Another area selection is already active.");
    }

    private Double parseLimit() {
	try {
	    return Double.parseDouble(limitEntry.text().trim().replace(',', '.'));
	} catch (NumberFormatException e) {
	    return null;
	}
    }

    /** Finds what would be slaughtered in the selected area and marks it red. */
    private void preview() {
	Double limit = parseLimit();
	if (ul == null || limit == null || running())
	    return;
	Utils.setpref(PREF_LIMIT, limitEntry.text().trim());
	AnimalStatsOverlay.Stat stat = statBox.sel;
	Gender gender = genderBox.sel;
	boolean skipYoung = youngBox.a;
	Map<Long, String> found = new LinkedHashMap<>();
	int memorized = 0, unknown = 0, noStat = 0;
	for (Gob g : Utils.getAllGobs(gui)) {
	    if (!inArea(g.rc))
		continue;
	    CattleId cid = g.getattr(CattleId.class);
	    if (cid == null)
		continue;
	    if (isDead(g))
		continue;
	    Entry e = cid.entry();
	    if (e == null) {
		unknown++;
		continue;
	    }
	    memorized++;
	    if (flag(e, "dead"))
		continue;
	    Boolean male = anyFlag(e, MALE_FIELDS);
	    if (gender == Gender.MALE && !Boolean.TRUE.equals(male))
		continue;
	    if (gender == Gender.FEMALE && !Boolean.FALSE.equals(male))
		continue;
	    if (skipYoung && Boolean.TRUE.equals(anyFlag(e, YOUNG_FIELDS)))
		continue;
	    Double v = AnimalStatsOverlay.value(e, stat);
	    if (v == null) {
		noStat++;
		continue;
	    }
	    if (v < limit)
		found.put(g.id, e.name + " (" + stat.label + " " + Math.round(v) + ")");
	}
	targets = found;
	StringBuilder sb = new StringBuilder();
	if (found.isEmpty())
	    sb.append("Nothing to butcher: no animal below ").append(fmt(limit)).append(" ").append(stat.menuName.toLowerCase()).append(".");
	else
	    sb.append(found.size()).append(" of ").append(memorized).append(" animals marked red. Press Start.");
	if (unknown > 0)
	    sb.append("\n").append(unknown).append(" not in the Cattle Roster (skipped; open the roster first).");
	if (noStat > 0)
	    sb.append("\n").append(noStat).append(" have no ").append(stat.menuName.toLowerCase()).append(" (skipped).");
	setInfo(sb.toString());
    }

    private static String fmt(double d) {
	return (d == Math.rint(d)) ? Long.toString((long)d) : Double.toString(d);
    }

    private boolean running() {
	Thread t = worker;
	return t != null && t.isAlive();
    }

    private void startOrStop() {
	if (running()) {
	    worker.interrupt();
	    worker = null;
	    gui.msg("Auto Butcher stopped.", Color.WHITE);
	    return;
	}
	if (targets.isEmpty() && !(carcassBox.a && ul != null)) {
	    gui.error("Auto Butcher: select an area with animals below the limit first.");
	    return;
	}
	if (gui.vhand != null) {
	    gui.error("Put down the item on your cursor first.");
	    return;
	}
	Work w = new Work(new ArrayList<>(targets.keySet()), carcassBox.a, rosterBox.a);
	Thread t = new Thread(w, "AutoButcher");
	t.setDaemon(true);
	worker = t;
	t.start();
    }

    @Override
    public void tick(double dt) {
	super.tick(dt);
	boolean run = running();
	if (run != shownRunning) {
	    shownRunning = run;
	    startBtn.change(run ? "Stop" : "Start");
	}
	String pi = pendingInfo;
	if (pi != null) {
	    pendingInfo = null;
	    setInfo(pi);
	}
	// Red tint on everything that is going to be slaughtered.
	Set<Long> want = targets.keySet();
	Set<Gob> now = new HashSet<>();
	for (Long id : want) {
	    Gob g = gui.map.glob.oc.getgob(id);
	    if (g != null && !isDead(g))
		now.add(g);
	}
	for (Iterator<Gob> i = marked.keySet().iterator(); i.hasNext(); ) {
	    Gob g = i.next();
	    if (!now.contains(g)) {
		synchronized (g) {
		    g.delattr(KillMark.class);
		}
		i.remove();
	    }
	}
	for (Gob g : now) {
	    if (!marked.containsKey(g)) {
		synchronized (g) {
		    g.setattr(new KillMark(g));
		}
		marked.put(g, true);
	    }
	}
    }

    private void close() {
	Thread t = worker;
	if (t != null)
	    t.interrupt();
	worker = null;
	targets = Collections.emptyMap();
	for (Gob g : marked.keySet()) {
	    synchronized (g) {
		g.delattr(KillMark.class);
	    }
	}
	marked.clear();
	Utils.setprefc("wndc-autoButcherWindow", this.c);
	reqdestroy();
	if (instance == this)
	    instance = null;
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
	if (sender == this && Objects.equals(msg, "close"))
	    close();
	else
	    super.wdgmsg(sender, msg, args);
    }

    // ---------------------------------------------------------------------------------------
    // Bot thread
    // ---------------------------------------------------------------------------------------

    private class Work implements Runnable {
	private final List<Long> todo;
	private final boolean carcasses, rosterRemove;

	Work(List<Long> todo, boolean carcasses, boolean rosterRemove) {
	    this.todo = todo;
	    this.carcasses = carcasses;
	    this.rosterRemove = rosterRemove;
	}

	@Override
	public void run() {
	    int killed = 0, failed = 0, butchered = 0;
	    String stopReason = null;
	    try {
		Gob player = gui.map.player();
		if (player == null)
		    return;
		if (player.getattr(Following.class) != null) {
		    gui.error("Auto Butcher: get off the vehicle first.");
		    return;
		}
		// Best sharp tool in hand once, up front (the same logic as the Butcher auto-equip option).
		if (CuttingToolAutoEquip.needsSwap(gui, CuttingToolAutoEquip.Policy.SHARP) || !holdsSharpTool())
		    CuttingToolAutoEquip.equipBest(gui, CuttingToolAutoEquip.Policy.SHARP);

		// Carcasses left in the area from an earlier, interrupted run go first.
		if (carcasses) {
		    for (Gob g : leftoverCarcasses()) {
			stopReason = butcher(g.id);
			if (stopReason != null)
			    break;
			butchered++;
		    }
		}
		List<Long> left = new ArrayList<>(todo);
		while (stopReason == null && !left.isEmpty() && !Thread.currentThread().isInterrupted()) {
		    long id = nearest(left);
		    left.remove(id);
		    Gob g = gob(id);
		    if (g == null || isDead(g)) {
			failed++;
			continue;
		    }
		    Entry e = entryOf(g);
		    if (!slaughter(id)) {
			failed++;
			continue;
		    }
		    killed++;
		    unmark(id);
		    if (rosterRemove && e != null)
			removeFromRoster(e);
		    if (carcasses) {
			stopReason = butcher(id);
			if (stopReason == null)
			    butchered++;
		    }
		}
	    } catch (InterruptedException ignored) {
		return;
	    } catch (Exception e) {
		e.printStackTrace();
		stopReason = "something went wrong (" + e + ")";
	    } finally {
		if (worker == Thread.currentThread())
		    worker = null;
	    }
	    if (Thread.currentThread().isInterrupted())
		return;
	    StringBuilder sb = new StringBuilder("Auto Butcher: slaughtered ").append(killed);
	    if (carcasses)
		sb.append(", butchered ").append(butchered);
	    if (failed > 0)
		sb.append(", ").append(failed).append(" couldn't be slaughtered");
	    if (stopReason != null)
		sb.append(". Stopped: ").append(stopReason);
	    String msg = sb.append(".").toString();
	    gui.msg(msg, Color.WHITE);
	    pendingInfo = msg.substring("Auto Butcher: ".length());
	}
    }

    private boolean holdsSharpTool() {
	Equipory eq = gui.getequipory();
	if (eq == null)
	    return true;
	for (int s : new int[]{6, 7}) {
	    WItem w = eq.slots[s];
	    if (w == null)
		continue;
	    try {
		String n = w.item.getname();
		if (n != null && (n.contains("Knife") || n.contains("Axe") || n.contains("Cleaver") || n.contains("Sword") || n.contains("Dagger")))
		    return true;
	    } catch (Exception ignored) {
	    }
	}
	return false;
    }

    /** Right-click, "Slaughter", then wait until the animal lies knocked over. */
    private boolean slaughter(long id) throws InterruptedException {
	Gob g = gob(id);
	if (g == null)
	    return false;
	FlowerMenu fm = clickForMenu(g, SLAUGHTER_TIMEOUT);
	if (fm == null)
	    return false;
	String opt = find(fm, "Slaughter");
	if (opt == null) {
	    fm.choose(null);
	    waitFor(() -> openFlowerMenu() == null, 2000);
	    return false;
	}
	fm.choose(fm.getPetalFromName(opt));
	long start = System.currentTimeMillis();
	long stall = stallMs(4000);
	long idleSince = -1;
	while (true) {
	    Gob a = gob(id);
	    if (a == null)
		return true; // gone already counts: it was slaughtered (or wandered out of view)
	    if (isDead(a))
		return true;
	    long now = System.currentTimeMillis();
	    if (now - start > SLAUGHTER_TIMEOUT)
		return false;
	    if (isBusy())
		idleSince = -1;
	    else if (idleSince < 0)
		idleSince = now;
	    else if (now - idleSince > stall)
		return false;
	    Thread.sleep(50);
	}
    }

    /**
     * Butchers a carcass until no carcass option is left (or it is gone). Null when finished, else why it stopped
     * (inventory full).
     */
    private String butcher(long id) throws InterruptedException {
	String last = null;
	int sameInRow = 0;
	for (int round = 0; round < 40; round++) {
	    if (Thread.currentThread().isInterrupted())
		throw new InterruptedException();
	    Gob g = gob(id);
	    if (g == null)
		return null;
	    FlowerMenu fm = clickForMenu(g, 30000);
	    if (fm == null)
		return null;
	    String opt = null;
	    for (String o : CARCASS_ORDER) {
		opt = find(fm, o);
		if (opt != null)
		    break;
	    }
	    if (opt == null) {
		fm.choose(null);
		waitFor(() -> openFlowerMenu() == null, 2000);
		return null;
	    }
	    int[] need = CARCASS_SPACE.get(opt);
	    if (need != null && !hasRoom(need[0], need[1], need[2])) {
		fm.choose(null);
		waitFor(() -> openFlowerMenu() == null, 2000);
		return "your inventory is full (empty it and press Start again; leftover carcasses are done first)";
	    }
	    sameInRow = opt.equals(last) ? sameInRow + 1 : 0;
	    if (sameInRow >= 6)
		return "\"" + opt + "\" keeps coming back on a carcass";
	    last = opt;
	    fm.choose(fm.getPetalFromName(opt));
	    waitWork();
	    if (gui.vhand != null)
		stashOrDrop();
	}
	return null;
    }

    /** Waits for the action to start and then for the character to be idle again. */
    private void waitWork() throws InterruptedException {
	long start = System.currentTimeMillis();
	long stall = stallMs(3000);
	long idleSince = -1;
	while (System.currentTimeMillis() - start < STEP_TIMEOUT) {
	    long now = System.currentTimeMillis();
	    if (isBusy())
		idleSince = -1;
	    else if (idleSince < 0)
		idleSince = now;
	    else if (now - idleSince > stall)
		return;
	    Thread.sleep(50);
	}
    }

    private boolean isBusy() {
	if (gui.prog != null)
	    return true;
	Gob p = gui.map.player();
	if (p == null)
	    return false;
	if (p.getv() > 0)
	    return true;
	for (String pose : p.getPoses()) {
	    if (pose.contains("butcher") || pose.contains("carving") || pose.contains("slaughter"))
		return true;
	}
	return false;
    }

    private boolean hasRoom(int w, int h, int n) {
	Inventory inv = gui.maininv;
	if (inv == null)
	    return true;
	return inv.isRoom(w, h) != null && inv.getFreeSpace() >= w * h * n;
    }

    private void stashOrDrop() throws InterruptedException {
	WItem v = gui.vhand;
	if (v == null)
	    return;
	Coord sz = v.sz.div(Inventory.sqsz);
	Coord c = gui.maininv != null ? gui.maininv.isRoom(Math.max(1, sz.x), Math.max(1, sz.y)) : null;
	if (c != null) {
	    gui.maininv.wdgmsg("drop", c);
	    if (waitFor(() -> gui.vhand == null, 2000))
		return;
	}
	Gob p = gui.map.player();
	if (p != null) {
	    gui.map.wdgmsg("drop", Coord.z, p.rc.floor(posres), 0);
	    waitFor(() -> gui.vhand == null, 2000);
	}
    }

    private List<Gob> leftoverCarcasses() {
	List<Gob> res = new ArrayList<>();
	if (ul == null)
	    return res;
	for (Gob g : Utils.getAllGobs(gui)) {
	    if (inArea(g.rc) && g.getattr(CattleId.class) != null && isDead(g))
		res.add(g);
	}
	Gob p = gui.map.player();
	if (p != null)
	    res.sort(Comparator.comparingDouble(g -> g.rc.dist(p.rc)));
	return res;
    }

    private void removeFromRoster(Entry e) {
	try {
	    CattleRoster<?> r = e.getparent(CattleRoster.class);
	    if (r != null)
		r.wdgmsg("rm", e.id);
	} catch (Exception ignored) {
	}
    }

    private void unmark(long id) {
	Map<Long, String> t = new LinkedHashMap<>(targets);
	t.remove(id);
	targets = t;
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private static Entry entryOf(Gob g) {
	CattleId cid = g.getattr(CattleId.class);
	return cid != null ? cid.entry() : null;
    }

    /** Knocked over (slaughtered) animals show a "knock"/"dead" pose. */
    private static boolean isDead(Gob g) {
	for (String p : g.getPoses()) {
	    if (p.contains("knock") || p.contains("dead"))
		return true;
	}
	return false;
    }

    private static final Map<String, Optional<Field>> boolFields = new HashMap<>();

    private static synchronized Boolean flagOrNull(Entry e, String name) {
	String key = e.getClass().getName() + "." + name;
	Optional<Field> f = boolFields.computeIfAbsent(key, k -> {
	    try {
		Field fl = e.getClass().getField(name);
		return fl.getType() == boolean.class ? Optional.of(fl) : Optional.empty();
	    } catch (NoSuchFieldException ex) {
		return Optional.empty();
	    }
	});
	if (!f.isPresent())
	    return null;
	try {
	    return f.get().getBoolean(e);
	} catch (IllegalAccessException ex) {
	    return null;
	}
    }

    private static boolean flag(Entry e, String name) {
	return Boolean.TRUE.equals(flagOrNull(e, name));
    }

    /** True/false from the first of these fields the entry has, null when it has none of them. */
    private static Boolean anyFlag(Entry e, String[] names) {
	for (String n : names) {
	    Boolean b = flagOrNull(e, n);
	    if (b != null)
		return b;
	}
	return null;
    }

    private FlowerMenu clickForMenu(Gob g, int timeout) throws InterruptedException {
	gui.map.wdgmsg("click", Coord.z, g.rc.floor(posres), 3, 0, 0, (int) g.id, g.rc.floor(posres), 0, -1);
	long start = System.currentTimeMillis();
	long stall = stallMs(3000);
	long idleSince = -1;
	while (true) {
	    FlowerMenu fm = openFlowerMenu();
	    if (fm != null)
		return fm;
	    long now = System.currentTimeMillis();
	    if (now - start > timeout)
		return null;
	    Gob p = gui.map.player();
	    if (p != null && p.getv() > 0)
		idleSince = -1;
	    else if (idleSince < 0)
		idleSince = now;
	    else if (now - idleSince > stall)
		return null; // stood still and no menu: couldn't reach it
	    Thread.sleep(50);
	}
    }

    private static String find(FlowerMenu fm, String name) {
	for (String o : fm.options) {
	    if (o.equals(name))
		return o;
	}
	return null;
    }

    private FlowerMenu openFlowerMenu() {
	for (Widget w = gui.ui.root.child; w != null; w = w.next) {
	    if (w instanceof FlowerMenu)
		return (FlowerMenu) w;
	}
	return null;
    }

    private long nearest(List<Long> ids) {
	Gob p = gui.map.player();
	long best = ids.get(0);
	double bd = Double.MAX_VALUE;
	for (long id : ids) {
	    Gob g = gob(id);
	    if (g == null || p == null)
		continue;
	    double d = g.rc.dist(p.rc);
	    if (d < bd) {
		bd = d;
		best = id;
	    }
	}
	return best;
    }

    private Gob gob(long id) {
	return gui.map.glob.oc.getgob(id);
    }

    private boolean inArea(Coord2d c) {
	return ul != null && c.x >= ul.x && c.x < br.x && c.y >= ul.y && c.y < br.y;
    }

    private static long stallMs(long min) {
	Integer ping = GameUI.getPingValue();
	return Math.max(min, (ping != null ? ping : 100) * 6L);
    }

    private static boolean waitFor(BooleanSupplier cond, int timeoutMs) throws InterruptedException {
	long start = System.currentTimeMillis();
	while (!cond.getAsBoolean()) {
	    if (System.currentTimeMillis() - start > timeoutMs)
		return false;
	    Thread.sleep(25);
	}
	return true;
    }

    // ---------------------------------------------------------------------------------------
    // Widgets
    // ---------------------------------------------------------------------------------------

    private static final ColorMask KILL_TINT = new ColorMask(new Color(255, 40, 40, 140));

    /** Red tint on the animals that are going to be slaughtered. */
    public static class KillMark extends GAttrib implements Gob.SetupMod {
	public KillMark(Gob g) {super(g);}
	public Pipe.Op gobstate() {return(KILL_TINT);}
    }

    private static final List<AnimalStatsOverlay.Stat> STATS = new ArrayList<>();
    static {
	for (AnimalStatsOverlay.Stat s : AnimalStatsOverlay.HIGHLIGHTS)
	    if (s.field != null)
		STATS.add(s);
    }

    private class StatBox extends OldDropBox<AnimalStatsOverlay.Stat> {
	StatBox(int w) {
	    super(w, STATS.size(), UI.scale(18));
	    String f = Utils.getpref(PREF_STAT, "meat");
	    sel = STATS.get(0);
	    for (AnimalStatsOverlay.Stat s : STATS)
		if (s.field.equals(f))
		    sel = s;
	}
	protected AnimalStatsOverlay.Stat listitem(int i) {return(STATS.get(i));}
	protected int listitems() {return(STATS.size());}
	protected void drawitem(GOut g, AnimalStatsOverlay.Stat item, int i) {
	    g.text(item.menuName + (item.percent ? " (%)" : ""), Coord.of(UI.scale(3), (itemh - Text.std.height()) / 2));
	}
	public void change(AnimalStatsOverlay.Stat item) {
	    super.change(item);
	    Utils.setpref(PREF_STAT, item.field);
	    preview();
	}
    }

    private class GenderBox extends OldDropBox<Gender> {
	GenderBox(int w) {
	    super(w, Gender.values().length, UI.scale(18));
	    try {
		sel = Gender.valueOf(Utils.getpref(PREF_GENDER, "BOTH"));
	    } catch (IllegalArgumentException e) {
		sel = Gender.BOTH;
	    }
	}
	protected Gender listitem(int i) {return(Gender.values()[i]);}
	protected int listitems() {return(Gender.values().length);}
	protected void drawitem(GOut g, Gender item, int i) {
	    g.text(item.label, Coord.of(UI.scale(3), (itemh - Text.std.height()) / 2));
	}
	public void change(Gender item) {
	    super.change(item);
	    Utils.setpref(PREF_GENDER, item.name());
	    preview();
	}
    }

    private class PrefBox extends CheckBox {
	private final String pref;
	PrefBox(String label, String pref, boolean def, String tip) {
	    super(label);
	    this.pref = pref;
	    a = Utils.getprefb(pref, def);
	    settip(tip);
	}
	public void set(boolean val) {
	    a = val;
	    Utils.setprefb(pref, val);
	    if (pref.equals(PREF_SKIP_YOUNG))
		preview();
	}
    }
}
