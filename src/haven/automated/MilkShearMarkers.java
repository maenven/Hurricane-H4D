package haven.automated;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.Entry;
import haven.res.ui.pag.toggle.Toggle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * H4D: shows a milk droplet above cattle, goats and sheep that have milk, and a wool icon (sheep wool or
 * mohair) above sheep and goats that can be sheared.
 *
 * <ul>
 * <li><b>Wool</b> is read live from the animal's model, as Ender's client does ({@code GobTag.FLEECE}): an
 * animal that has wool carries a model part whose resource name contains {@code -fleece}; shearing removes it.
 * Works for every sheep/goat in view, memorized or not.</li>
 * <li><b>Milk</b> has no visible sign. The Cattle Roster's {@code lactate} flag only says the animal produces
 * milk, so the client also remembers when it saw each animal milked (by hand or by the Milk Animals button)
 * and estimates the refill from the roster's milk quantity (wiki: quantity * 0.01 L per 10 min). The droplet
 * shows once {@link #MIN_LITERS} has refilled; an animal never seen milked counts as having milk.</li>
 * </ul>
 * The icons sit above the name/stats that {@link CattleId} draws ({@link CattleId#drawnHeight}).
 */
public class MilkShearMarkers {
    public static final String PREF_ENABLED = "milkShearMarkers";
    private static volatile boolean enabled = Utils.getprefb(PREF_ENABLED, false);

    public static boolean enabled() {return(enabled);}

    public static void setEnabled(boolean val, UI ui) {
	enabled = val;
	Utils.setprefb(PREF_ENABLED, val);
	if(ui != null && ui.gui != null)
	    ui.gui.optionInfoMsg("Milkable/Shearable Animal Markers are now " + (val ? "ENABLED" : "DISABLED") + ".", (val ? OptWnd.msgGreen : OptWnd.msgRed), Audio.resclip(val ? Toggle.sfxon : Toggle.sfxoff));
    }

    public static void toggle(UI ui) {setEnabled(!enabled, ui);}

    /** Animals that can be milked or sheared, by gob resource. */
    public static boolean isMilkOrWoolAnimal(String resname) {
	if(resname == null || !resname.startsWith("gfx/kritter/"))
	    return(false);
	return((resname.contains("/cattle/") && !resname.contains("/aurochs"))
	       || (resname.contains("/goat/") && !resname.contains("wild"))
	       || (resname.contains("/sheep/") && !resname.contains("wild"))
	       || resname.contains("/reindeer/") || resname.contains("/teimdeer/"));
    }

    /** True when the animal's model currently shows wool. */
    public static boolean shearable(Gob gob) {
	Drawable d = gob.getattr(Drawable.class);
	if(!(d instanceof Composite))
	    return(false);
	try {
	    for(Composited.MD md : ((Composite)d).comp.cmod) {
		Resource r = md.mod.get();
		if(r != null && r.name.contains("-fleece"))
		    return(true);
	    }
	} catch(Loading | java.util.ConcurrentModificationException e) {
	    return(false);
	}
	return(false);
    }

    private static final Map<Class<?>, Map<String, Optional<Field>>> fields = new ConcurrentHashMap<>();

    private static Optional<Field> field(Entry e, String name) {
	return(fields.computeIfAbsent(e.getClass(), k -> new ConcurrentHashMap<>())
	    .computeIfAbsent(name, n -> {
		try {
		    return(Optional.of(e.getClass().getField(n)));
		} catch(NoSuchFieldException ex) {
		    return(Optional.empty());
		}
	    }));
    }

    private static boolean flag(Entry e, String name) {
	Optional<Field> f = field(e, name);
	if(!f.isPresent())
	    return(false);
	try {
	    Object v = f.get().get(e);
	    return((v instanceof Boolean) && (Boolean)v);
	} catch(IllegalAccessException ex) {
	    return(false);
	}
    }

    /** True when the Cattle Roster lists the animal as lactating (it produces milk, whether or not it has any now). */
    public static boolean milkable(Gob gob) {
	CattleId cid = gob.getattr(CattleId.class);
	Entry e = (cid == null) ? null : cid.entry();
	if(e == null)
	    return(false);
	return(flag(e, "lactate") && !flag(e, "dead"));
    }

    /** Lactating and, by the estimate below, with at least {@link #MIN_LITERS} of milk in it. */
    public static boolean hasMilk(Gob gob) {
	CattleId cid = gob.getattr(CattleId.class);
	Entry e = (cid == null) ? null : cid.entry();
	if(e == null || !flag(e, "lactate") || flag(e, "dead"))
	    return(false);
	return(estimatedMilk(gob, e) >= MIN_LITERS);
    }

    /* ---- Milk availability: the roster only says the animal is lactating, not that it has milk now. ----
     * The wiki (ringofbrodgar "Milk") gives the refill rate: Milk Quantity * 0.01 L per 10 minutes, capped at
     * 25 L. So the client remembers when it saw each animal milked and estimates what has refilled since. An
     * animal never seen milked is assumed to have milk. */
    public static final String PREF_MILKED = "milkShearMilkedAt";
    /** Show the milk icon once this many liters have refilled. */
    public static final double MIN_LITERS = 1.0;
    private static final long KEEP_MS = 3L * 24 * 3600 * 1000;
    private static final Map<Long, Long> milkedAt = new ConcurrentHashMap<>();
    static {
	String raw = Utils.getpref(PREF_MILKED, "");
	long now = System.currentTimeMillis();
	for(String part : raw.split(",")) {
	    int i = part.indexOf(':');
	    if(i <= 0) continue;
	    try {
		long id = Long.parseLong(part.substring(0, i)), t = Long.parseLong(part.substring(i + 1));
		if(now - t < KEEP_MS)
		    milkedAt.put(id, t);
	    } catch(NumberFormatException ignored) {}
	}
    }

    private static void saveMilked() {
	long now = System.currentTimeMillis();
	StringBuilder sb = new StringBuilder();
	for(Map.Entry<Long, Long> e : milkedAt.entrySet()) {
	    if(now - e.getValue() >= KEEP_MS) {
		milkedAt.remove(e.getKey());
		continue;
	    }
	    if(sb.length() > 0) sb.append(',');
	    sb.append(e.getKey()).append(':').append(e.getValue());
	}
	Utils.setpref(PREF_MILKED, sb.toString());
    }

    private static UID uid(Gob gob) {
	CattleId cid = gob.getattr(CattleId.class);
	return((cid == null) ? null : cid.id);
    }

    /** Remember that {@code gob} was just milked (its milk is now empty). */
    public static void noteMilked(Gob gob) {
	UID id = (gob == null) ? null : uid(gob);
	if(id == null) return;
	milkedAt.put(id.bits, System.currentTimeMillis());
	saveMilked();
    }

    private static Double num(Entry e, String name) {
	Optional<Field> f = field(e, name);
	if(!f.isPresent()) return(null);
	try {
	    Object v = f.get().get(e);
	    return((v instanceof Number) ? ((Number)v).doubleValue() : null);
	} catch(IllegalAccessException ex) {
	    return(null);
	}
    }

    /** Estimated liters in the animal now, or +Inf when it was never seen milked. */
    public static double estimatedMilk(Gob gob, Entry e) {
	Long t = milkedAt.get(e.id.bits);
	if(t == null)
	    return(Double.POSITIVE_INFINITY);
	Double q = num(e, "milk");
	double rate = (q == null) ? 0 : q * 0.01; // liters per 10 minutes
	double l = rate * ((System.currentTimeMillis() - t) / 600000.0);
	return(Math.min(l, 25.0));
    }

    /* A milking the player started by hand: a bucket/container used on the animal, Shift+right-click with a
     * barrel carried, or a "Milk" flower-menu option. The animal is marked milked right away (milking it empties
     * it, and so does a "no milk" error); only an error about the container being full undoes it, since the
     * animal then still has milk. */
    private static class Pending {
	final long id; final long start; final int errSeq; final Long prev;
	Pending(long id, long start, int errSeq, Long prev) {this.id = id; this.start = start; this.errSeq = errSeq; this.prev = prev;}
    }
    private static volatile Pending pending = null;
    /** The animal last right-clicked, for a "Milk" flower-menu choice. */
    private static volatile Gob lastClicked = null;

    private static boolean carriesSomething(GameUI gui) {
	long pl = gui.map.plgob;
	if(pl < 0) return(false);
	for(Gob g : Utils.getAllGobs(gui)) {
	    Following f = g.getattr(Following.class);
	    if(f != null && f.tgt == pl)
		return(true);
	}
	return(false);
    }

    private static void startMilking(GameUI gui, Gob gob) {
	UID id = uid(gob);
	if(id == null || !milkable(gob))
	    return;
	pending = new Pending(id.bits, System.currentTimeMillis(), gui.ui.serverErrorSeq, milkedAt.get(id.bits));
	noteMilked(gob);
    }

    /** Called by MapView on a right-click on a gob. */
    public static void noteClick(GameUI gui, Gob gob, boolean shift) {
	if(gui == null || gob == null)
	    return;
	lastClicked = gob;
	if(shift && milkable(gob) && carriesSomething(gui))
	    startMilking(gui, gob);
    }

    /** Called by MapView when the held item is used on a gob. Any held item counts: milking (bucket, barrel,
     * cup, ...) is the only thing an item does to a lactating animal, apart from shears. */
    public static void noteItemAct(GameUI gui, Gob gob) {
	if(gui == null || gob == null || !milkable(gob))
	    return;
	String res = null;
	try {
	    if(gui.vhand != null)
		res = gui.vhand.item.getres().name;
	} catch(Loading ignored) {}
	if(res != null && res.contains("shears"))
	    return;
	startMilking(gui, gob);
    }

    /** Called by FlowerMenu when an option is chosen. */
    public static void noteFlowerChoice(GameUI gui, String option) {
	Gob g = lastClicked;
	if(gui != null && g != null && option != null && option.toLowerCase(java.util.Locale.ROOT).contains("milk"))
	    startMilking(gui, g);
    }

    /** Called from GameUI.tick: undo a milking the server refused because the container is full. */
    public static void tick(GameUI gui) {
	Pending p = pending;
	if(p == null)
	    return;
	if(System.currentTimeMillis() - p.start > 60000) {
	    pending = null;
	    return;
	}
	if(gui.ui.serverErrorSeq != p.errSeq) {
	    pending = null;
	    String t = gui.ui.lastServerError;
	    t = (t == null) ? "" : t.toLowerCase(java.util.Locale.ROOT);
	    if(t.contains("full") || t.contains("no room") || t.contains("no space")) {
		if(p.prev == null) milkedAt.remove(p.id); else milkedAt.put(p.id, p.prev);
		saveMilked();
	    }
	}
    }

    /* ---- Icons ---- */
    public static final String MILK_ICON = "gfx/hud/rosters/milkquantity";
    public static final String SHEEP_WOOL_ICON = "gfx/invobjs/wool-sheep";
    public static final String GOAT_WOOL_ICON = "gfx/invobjs/wool-goat";
    private static final Map<String, Indir<Resource>> iconRes = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> iconImg = new ConcurrentHashMap<>();
    private static final Map<String, Tex> labels = new ConcurrentHashMap<>();

    /** The icon image, or null while it is still loading. */
    private static BufferedImage icon(String name) {
	BufferedImage img = iconImg.get(name);
	if(img != null) return(img);
	try {
	    Resource r = iconRes.computeIfAbsent(name, n -> Resource.remote().load(n)).get();
	    BufferedImage raw = r.layer(Resource.imgc).img;
	    int h = UI.scale(20);
	    int w = Math.max(1, raw.getWidth() * h / Math.max(1, raw.getHeight()));
	    img = PUtils.convolvedown(raw, Coord.of(w, h), CharWnd.iconfilter);
	    iconImg.put(name, img);
	    return(img);
	} catch(Loading l) {
	    return(null);
	} catch(Exception e) {
	    return(null);
	}
    }

    private static String woolIcon(Gob gob) {
	try {
	    Resource r = gob.getres();
	    if(r != null && r.name.contains("/goat/"))
		return(GOAT_WOOL_ICON);
	} catch(Loading ignored) {}
	return(SHEEP_WOOL_ICON);
    }

    /** The label for milk and/or the given wool icon, or null while an icon is loading. */
    private static Tex label(boolean milk, String wool) {
	String key = (milk ? "m" : "") + "|" + ((wool == null) ? "" : wool);
	Tex t = labels.get(key);
	if(t != null) return(t);
	BufferedImage m = milk ? icon(MILK_ICON) : null;
	BufferedImage w = (wool != null) ? icon(wool) : null;
	if((milk && m == null) || (wool != null && w == null))
	    return(null);
	BufferedImage img = (m != null && w != null) ? ItemInfo.catimgsh(UI.scale(2), m, w) : ((m != null) ? m : w);
	t = new TexI(img);
	labels.put(key, t);
	return(t);
    }

    /** Gob attribute that draws the label. Attached in {@code Gob.init} to every {@link #isMilkOrWoolAnimal} gob. */
    public static class Marker extends GAttrib implements RenderTree.Node, PView.Render2D {
	private int kind = 0;
	private double since = 1;

	public Marker(Gob gob) {super(gob);}

	public void ctick(double dt) {
	    if(!enabled) {
		kind = 0;
		return;
	    }
	    since += dt;
	    if(since < 0.5)
		return;
	    since = 0;
	    boolean dead = Boolean.TRUE.equals(gob.knocked);
	    int k = 0;
	    if(!dead) {
		if(hasMilk(gob)) k |= 1;
		if(shearable(gob)) k |= 2;
	    }
	    kind = k;
	}

	public void draw(GOut g, Pipe state) {
	    int k = kind;
	    if(!enabled || k == 0)
		return;
	    Coord sc = Homo3D.obj2view(new Coord3f(0, 0, 15), state, Area.sized(g.sz())).round2();
	    if(!sc.isect(Coord.z, g.sz()))
		return;
	    CattleId cid = gob.getattr(CattleId.class);
	    int up = (cid == null) ? 0 : cid.drawnHeight();
	    Tex t = label((k & 1) != 0, ((k & 2) != 0) ? woolIcon(gob) : null);
	    if(t != null)
		g.aimage(t, sc.sub(0, up), 0.5, 1.0);
	}
    }
}
