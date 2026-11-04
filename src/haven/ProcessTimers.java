package haven;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H4D: finish timers for smelters, ovens, kilns, crucibles and the like (Ender/Kami style).
 *
 * The server only reports progress (the items' meters) while the object's window is open, so the
 * timer is computed when you look inside and then counts down on its own after you close it.
 * The full processing time of an item is learned by watching its meter tick twice; learned times
 * are saved per (object, item) so the next look gives a timer straight away. Ore smelting and the
 * finery forge start from known times. Ovens and kilns also get a fire timer from their fuel bar,
 * and dough put into an oven while its window is open gets a baking timer. Timers pause while unlit.
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

    /*
     * Fuel: the fuel bar in the window is a VMeter whose value is the share of a full load.
     * A full oven/kiln holds 30 units (1 branch = 1 unit, Nurgling2's FuelLvl), and one unit burns
     * 15 in-game minutes = about 273.6 real seconds (Ring of Brodgar Kiln table: 8 units = 36:29).
     * Baked goods need 4 units of fuel (wiki Oven page).
     */
    public static final double SECS_PER_FUEL_UNIT = 2189.0 / 8;
    private static final Map<String, Double> FUEL_UNITS = new HashMap<>();
    static {
	FUEL_UNITS.put("gfx/terobjs/oven", 30.0);
	FUEL_UNITS.put("gfx/terobjs/kiln", 30.0);
    }
    private static final double BAKE_UNITS = 4;
    /** Fire bit in each object's sdt (Nurgling2's LightObject; the same bits Light Fires in Area uses). */
    private static final Map<String, Integer> FIRE_BIT = new HashMap<>();
    static {
	FIRE_BIT.put("gfx/terobjs/smelter", 2);
	FIRE_BIT.put("gfx/terobjs/primsmelter", 1);
	FIRE_BIT.put("gfx/terobjs/oven", 4);
	FIRE_BIT.put("gfx/terobjs/kiln", 1);
	FIRE_BIT.put("gfx/terobjs/smokeshed", 16);
	FIRE_BIT.put("gfx/terobjs/tarkiln", 16);
	FIRE_BIT.put("gfx/terobjs/fineryforge", 8);
	FIRE_BIT.put("gfx/terobjs/steelcrucible", 4);
	FIRE_BIT.put("gfx/terobjs/crucible", 4);
    }

    public static class Entry {
	public final String gobres;
	public volatile double nextEnd = -1, allEnd = -1; // item timers, Utils.rtime() seconds; -1 = unknown
	public volatile double pct = -1;                 // highest meter shown when the time is unknown
	public volatile double bakeEnd = -1;             // oven: when dough put in while watching is baked
	public volatile double fireEnd = -1;             // when the fuel runs out; -1 = unknown
	public volatile boolean paused = false;
	Entry(String gobres) {this.gobres = gobres;}
    }

    private static class Bound {
	final Window wnd; final long gobid; final String gobres;
	boolean first = true;                      // the first look at the window's contents
	final Set<GItem> seen = Collections.newSetFromMap(new WeakHashMap<>());
	final Map<GItem, Double> bakeEnds = new WeakHashMap<>(); // dough put in while the window was open
	double lastFuel = -1, lastFuelChange = -1;
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
		if(t.bakeEnd > now) t.bakeEnd += dt;
		if(t.fireEnd > now) t.fireEnd += dt;
		synchronized(bound) {
		    for(Bound b : bound) {
			if(b.gobid == e.getKey())
			    b.bakeEnds.replaceAll((k, v) -> (v > now) ? v + dt : v);
		    }
		}
	    }
	    /* Forget timers that finished more than an hour ago. */
	    double last = Math.max(Math.max(t.allEnd, t.bakeEnd), t.fireEnd);
	    if(last > 0 && now - last > 3600)
		timers.remove(e.getKey());
	}
    }

    private static void update(Bound b, double now) {
	double next = Double.MAX_VALUE, all = -1, pct = -1;
	boolean any = false, unknown = false, items = false;
	boolean oven = b.gobres.equals("gfx/terobjs/oven");
	double fuel = fuel(b.wnd);
	double fireEnd = -1;
	if(fuel >= 0) {
	    if(b.lastFuel >= 0 && fuel < b.lastFuel) {
		if(b.lastFuelChange >= 0 && b.lastFuel - fuel <= 0.1)
		    learnFuel(b.gobres, (now - b.lastFuelChange) / (b.lastFuel - fuel));
		b.lastFuelChange = now;
	    } else if(fuel > b.lastFuel) {
		b.lastFuelChange = -1; // refuelled: the next drop is not a full step
	    }
	    b.lastFuel = fuel;
	    double full = fuelFull(b.gobres);
	    if(full > 0)
		fireEnd = now + fuel * full;
	}
	double bake = -1;
	for(WItem w : b.wnd.children(WItem.class)) {
	    items = true;
	    if(oven) {
		/* Dough put in while we watch bakes after 4 more units of fuel burn. */
		if(!b.first && !b.seen.contains(w.item) && !b.bakeEnds.containsKey(w.item))
		    b.bakeEnds.put(w.item, now + BAKE_UNITS * SECS_PER_FUEL_UNIT);
		Double be = b.bakeEnds.get(w.item);
		if(be != null) bake = Math.max(bake, be);
	    }
	    b.seen.add(w.item);
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
	b.first = false;
	boolean fireUseful = (fireEnd >= 0) && (items || fuel > 0);
	if(!any && bake < 0 && !fireUseful) {
	    timers.remove(b.gobid);
	    return;
	}
	Entry e = timers.computeIfAbsent(b.gobid, k -> new Entry(b.gobres));
	if(!any) {
	    e.nextEnd = -1; e.allEnd = -1; e.pct = -1;
	} else if(unknown && all < 0) {
	    e.nextEnd = -1; e.allEnd = -1; e.pct = pct;
	} else {
	    e.nextEnd = next; e.allEnd = all; e.pct = -1;
	}
	/* Baking can't finish after the fire is out. */
	e.bakeEnd = (bake >= 0 && fireEnd >= 0 && bake > fireEnd && fuel > 0) ? -1 : bake;
	e.fireEnd = fireUseful ? fireEnd : -1;
    }

    /** The window's fuel bar (0..1), or -1 if it has none. */
    private static double fuel(Window wnd) {
	double any = -1;
	for(VMeter vm : wnd.children(VMeter.class)) {
	    for(LayerMeter.Meter m : vm.meters) {
		Color c = m.c;
		if(c.getRed() == 255 && c.getGreen() == 128 && c.getBlue() == 0)
		    return m.a;
		if(any < 0) any = m.a;
	    }
	}
	return any;
    }

    /** Seconds a full fuel bar burns, learned or from the wiki numbers; -1 if unknown. */
    private static synchronized double fuelFull(String gobres) {
	String k = "procfuel/" + gobres;
	Double v = learned.get(k);
	if(v == null) {
	    try {v = Double.parseDouble(Utils.getpref(k, "-1"));} catch(NumberFormatException e) {v = -1.0;}
	    learned.put(k, v);
	}
	if(v > 0) return v;
	Double units = FUEL_UNITS.get(gobres);
	return (units == null) ? -1 : units * SECS_PER_FUEL_UNIT;
    }

    private static synchronized void learnFuel(String gobres, double full) {
	if(full < 60 || full > 7 * 24 * 3600) return;
	String k = "procfuel/" + gobres;
	Double old = learned.get(k);
	double v = (old != null && old > 0) ? (old + full) / 2 : full;
	learned.put(k, v);
	Utils.setpref(k, String.valueOf(Math.round(v)));
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
	Integer bit = (res == null) ? null : FIRE_BIT.get(res);
	if(bit == null) return null;
	Message m = gob.sdtm();
	if(m.eom()) return null;
	return (m.uint8() & bit) != 0;
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
