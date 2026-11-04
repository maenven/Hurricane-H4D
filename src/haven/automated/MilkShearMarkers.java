package haven.automated;

import haven.*;
import haven.render.Homo3D;
import haven.render.Pipe;
import haven.render.RenderTree;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.Entry;
import haven.res.ui.pag.toggle.Toggle;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H4D: a wool icon (sheep wool or mohair) above sheep and goats that can be sheared, and a crossed-out milk
 * droplet above milk animals that have just been milked and have no milk.
 *
 * <ul>
 * <li><b>Wool</b> is read live from the animal's model, as Ender's client does ({@code GobTag.FLEECE}): an
 * animal that has wool carries a model part whose resource name contains {@code -fleece}; shearing removes it.
 * Reindeer have a fleece part too but can't be sheared, so they never get it.</li>
 * <li><b>No milk:</b> the server never tells the client how much milk an animal holds (the roster's
 * {@code lactate} only says it produces milk). So the client marks an animal empty when it sees it emptied:
 * the Milk Animals button milks it; a manual milking (bucket/barrel) plays its sound; or a manual attempt
 * doesn't even make the character walk there (the server refused: no milk). A "full" error never marks it.
 * The mark clears once 0.1 L should have refilled (wiki: Milk Quantity * 0.01 L per 10 min; Ian: milk refills
 * in 0.1 L steps), or after {@link #UNKNOWN_REFILL_MS} when the roster isn't loaded.</li>
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
	       || isDeer(resname));
    }

    private static boolean isDeer(String resname) {
	return(resname.contains("/reindeer/") || resname.contains("/teimdeer/"));
    }

    private static String resname(Gob gob) {
	try {
	    Resource r = gob.getres();
	    return((r == null) ? null : r.name);
	} catch(Loading l) {
	    return(null);
	}
    }

    /** Base resource plus model-part names, e.g. "gfx/kritter/sheep/sheep gfx/kritter/sheep/ewe ...". */
    private static String parts(Gob gob) {
	StringBuilder sb = new StringBuilder();
	String rn = resname(gob);
	if(rn != null) sb.append(rn);
	Drawable d = gob.getattr(Drawable.class);
	if(d instanceof Composite) {
	    try {
		for(Composited.MD md : ((Composite)d).comp.cmod) {
		    Resource r = md.mod.get();
		    if(r != null) sb.append(' ').append(r.name);
		}
	    } catch(Loading | java.util.ConcurrentModificationException ignored) {}
	}
	return(sb.toString());
    }

    /** True when the animal's model currently shows wool and it can be sheared (not reindeer). */
    public static boolean shearable(Gob gob) {
	String rn = resname(gob);
	if(rn == null || isDeer(rn))
	    return(false);
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

    private static Entry entry(Gob gob) {
	CattleId cid = gob.getattr(CattleId.class);
	return((cid == null) ? null : cid.entry());
    }

    /**
     * True when the animal can normally be milked: the Cattle Roster lists it as lactating, or, when it isn't
     * in a loaded roster, it is an adult female (cow, ewe, nanny).
     */
    public static boolean milkable(Gob gob) {
	Entry e = entry(gob);
	if(e != null)
	    return(flag(e, "lactate") && !flag(e, "dead"));
	String p = parts(gob);
	return(p.contains("/cattle/cow") || p.contains("/sheep/ewe") || p.contains("/goat/nanny"));
    }

    /* ---- "Milked, no milk now" marks ---- */
    public static final String PREF_MILKED = "milkShearMilkedAt";
    /** Milk refills in 0.1 L steps (Ian); one step is enough to milk again. */
    public static final double REFILL_LITERS = 0.1;
    /** How long the mark stays when the roster's milk quantity isn't known. */
    public static final long UNKNOWN_REFILL_MS = 20L * 60 * 1000;
    private static final long KEEP_MS = 3L * 24 * 3600 * 1000;
    /** Roster animal UID bits -> time it was emptied; saved. */
    private static final Map<Long, Long> milkedAt = new ConcurrentHashMap<>();
    /** Gob id -> time it was emptied, for animals not in a loaded roster; this session only. */
    private static final Map<Long, Long> milkedGob = new ConcurrentHashMap<>();
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

    /** Remember that {@code gob} was just emptied of milk. */
    public static void noteMilked(Gob gob) {
	if(gob == null) return;
	CattleId cid = gob.getattr(CattleId.class);
	long now = System.currentTimeMillis();
	milkedGob.put(gob.id, now);
	if(cid != null) {
	    milkedAt.put(cid.id.bits, now);
	    saveMilked();
	}
    }

    /** True while the animal is marked as having no milk. */
    public static boolean noMilk(Gob gob) {
	if(!milkable(gob))
	    return(false);
	CattleId cid = gob.getattr(CattleId.class);
	Long t = (cid != null) ? milkedAt.get(cid.id.bits) : null;
	if(t == null)
	    t = milkedGob.get(gob.id);
	if(t == null)
	    return(false);
	long el = System.currentTimeMillis() - t;
	Entry e = entry(gob);
	Double q = (e == null) ? null : num(e, "milk");
	if(q == null || q <= 0)
	    return(el < UNKNOWN_REFILL_MS);
	double perMs = q * 0.01 / 600000.0; // liters per ms
	return(el * perMs < REFILL_LITERS);
    }

    /* ---- Manual milking: a bucket/container used on the animal, a right-click with a barrel carried, or a
     * "Milk" flower-menu option. Afterwards (within 30 s, before any error):
     *  - a sound on the animal or the player, or a UI sound -> milked, empty;
     *  - an error mentioning milk but not "full" (e.g. "no milk") -> empty;
     *  - the character never starts walking or working within 2.5 s -> the server refused: empty;
     *  - an error about the container being full -> nothing. */
    private static class Pending {
	final Gob gob; final long start; final int errSeq, sfxSeq;
	volatile boolean started = false;
	Pending(Gob gob, long start, int errSeq, int sfxSeq) {
	    this.gob = gob; this.start = start; this.errSeq = errSeq; this.sfxSeq = sfxSeq;
	}
    }
    private static volatile Pending pending = null;
    private static volatile long playerId = -1;
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
	if(gob == null || !milkable(gob))
	    return;
	playerId = gui.map.plgob;
	pending = new Pending(gob, System.currentTimeMillis(), gui.ui.serverErrorSeq, gui.ui.serverSfxSeq);
    }

    /** Called by MapView on a right-click on a gob. */
    public static void noteClick(GameUI gui, Gob gob, boolean shift) {
	if(gui == null || gob == null)
	    return;
	lastClicked = gob;
	if(carriesSomething(gui)) // a barrel over the head
	    startMilking(gui, gob);
    }

    /** Called by MapView when the held item is used on a gob (a bucket, cup, ...; not shears). */
    public static void noteItemAct(GameUI gui, Gob gob) {
	if(gui == null || gob == null)
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
	if(gui != null && g != null && option != null && option.toLowerCase(Locale.ROOT).contains("milk"))
	    startMilking(gui, g);
    }

    private static boolean milkingSound(String name) {
	if(name == null) return(false);
	String n = name.toLowerCase(Locale.ROOT);
	if(n.contains("milk") || n.contains("pour") || n.contains("liquid") || n.contains("splash") || n.contains("squirt")
	   || n.contains("water") || n.contains("fill") || n.contains("drip"))
	    return(true);
	return(!n.contains("kritter")); // any other sound but the animals' own calls
    }

    private static void confirm(Pending p) {
	pending = null;
	noteMilked(p.gob);
    }

    /** Called by AudioSprite when a positional sound starts on {@code gob} (null when unknown). */
    public static void noteSound(Gob gob, String name) {
	Pending p = pending;
	if(p == null || System.currentTimeMillis() - p.start > 30000)
	    return;
	if(gob != null && gob != p.gob && gob.id != playerId)
	    return;
	if(milkingSound(name))
	    confirm(p);
    }

    private static boolean saysFull(String t) {
	return(t.contains("full") || t.contains("no room") || t.contains("no space"));
    }

    /** Called from GameUI.tick. */
    public static void tick(GameUI gui) {
	Pending p = pending;
	if(p == null || gui.map == null)
	    return;
	long el = System.currentTimeMillis() - p.start;
	if(el > 30000) {
	    pending = null;
	    return;
	}
	if(gui.ui.serverErrorSeq != p.errSeq) {
	    pending = null;
	    String t = gui.ui.lastServerError;
	    t = (t == null) ? "" : t.toLowerCase(Locale.ROOT);
	    if(t.contains("milk") && !saysFull(t))
		confirm(p); // e.g. "no milk"
	    return;
	}
	if(gui.ui.serverSfxSeq != p.sfxSeq && milkingSound(gui.ui.lastServerSfx)) {
	    confirm(p);
	    return;
	}
	Gob pl = gui.map.player();
	if((pl != null && pl.getv() > 0) || gui.prog != null)
	    p.started = true;
	if(!p.started && el > 2500)
	    confirm(p); // didn't even walk there: nothing to milk
    }

    /* ---- Icons ---- */
    public static final String MILK_ICON = "gfx/hud/rosters/milkquantity";
    public static final String SHEEP_WOOL_ICON = "gfx/invobjs/wool-sheep";
    public static final String GOAT_WOOL_ICON = "gfx/invobjs/wool-goat";
    private static final String NO_MILK = "nomilk";
    private static final Map<String, Indir<Resource>> iconRes = new ConcurrentHashMap<>();
    private static final Map<String, BufferedImage> iconImg = new ConcurrentHashMap<>();
    private static final Map<String, Tex> labels = new ConcurrentHashMap<>();

    /** The icon image, or null while it is still loading. */
    private static BufferedImage icon(String name) {
	BufferedImage img = iconImg.get(name);
	if(img != null) return(img);
	try {
	    if(name.equals(NO_MILK)) {
		BufferedImage m = icon(MILK_ICON);
		if(m == null) return(null);
		img = crossed(m);
	    } else {
		Resource r = iconRes.computeIfAbsent(name, n -> Resource.remote().load(n)).get();
		BufferedImage raw = r.layer(Resource.imgc).img;
		int h = UI.scale(20);
		int w = Math.max(1, raw.getWidth() * h / Math.max(1, raw.getHeight()));
		img = PUtils.convolvedown(raw, Coord.of(w, h), CharWnd.iconfilter);
	    }
	    iconImg.put(name, img);
	    return(img);
	} catch(Loading l) {
	    return(null);
	} catch(Exception e) {
	    return(null);
	}
    }

    /** The image with a red X over it. */
    private static BufferedImage crossed(BufferedImage src) {
	BufferedImage img = TexI.mkbuf(Coord.of(src.getWidth(), src.getHeight()));
	Graphics2D g = img.createGraphics();
	g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	g.drawImage(src, 0, 0, null);
	int w = src.getWidth(), h = src.getHeight(), m = Math.max(1, Math.min(w, h) / 8);
	g.setColor(Color.BLACK);
	g.setStroke(new BasicStroke(Math.max(3f, Math.min(w, h) / 5f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
	g.drawLine(m, m, w - 1 - m, h - 1 - m);
	g.drawLine(w - 1 - m, m, m, h - 1 - m);
	g.setColor(new Color(230, 30, 30));
	g.setStroke(new BasicStroke(Math.max(2f, Math.min(w, h) / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
	g.drawLine(m, m, w - 1 - m, h - 1 - m);
	g.drawLine(w - 1 - m, m, m, h - 1 - m);
	g.dispose();
	return(img);
    }

    private static String woolIcon(Gob gob) {
	String rn = resname(gob);
	return((rn != null && rn.contains("/goat/")) ? GOAT_WOOL_ICON : SHEEP_WOOL_ICON);
    }

    /** The label for the "no milk" mark and/or the given wool icon, or null while an icon is loading. */
    private static Tex label(boolean noMilk, String wool) {
	String key = (noMilk ? "x" : "") + "|" + ((wool == null) ? "" : wool);
	Tex t = labels.get(key);
	if(t != null) return(t);
	BufferedImage m = noMilk ? icon(NO_MILK) : null;
	BufferedImage w = (wool != null) ? icon(wool) : null;
	if((noMilk && m == null) || (wool != null && w == null))
	    return(null);
	BufferedImage img = (m != null && w != null) ? ItemInfo.catimgsh(UI.scale(2), m, w) : ((m != null) ? m : w);
	t = new TexI(img);
	labels.put(key, t);
	return(t);
    }

    /** Gob attribute that draws the icons. Attached in {@code Gob.init} to every {@link #isMilkOrWoolAnimal} gob. */
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
		if(noMilk(gob)) k |= 1;
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
