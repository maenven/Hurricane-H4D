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

import static haven.PUtils.blurmask2;
import static haven.PUtils.rasterimg;

/**
 * H4D: marks cattle, goats and sheep that can be milked ("Milk") or sheared ("Wool") with a label above them.
 *
 * <ul>
 * <li><b>Wool</b> is read live from the animal's model, as Ender's client does ({@code GobTag.FLEECE}): an
 * animal that has wool carries a model part whose resource name contains {@code -fleece}; shearing removes it.
 * Works for every sheep/goat in view, memorized or not.</li>
 * <li><b>Milk</b> has no visible sign on the model, so it comes from the Cattle Roster's lactation column
 * (field {@code lactate} of the server's per-species entry class, as in Nurgling2's
 * {@code MilkAnimalsAction}). Only memorized animals, and only once the roster has been opened this
 * session.</li>
 * </ul>
 * The label sits above the name/stats that {@link CattleId} draws ({@link CattleId#drawnHeight}).
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

    private static boolean flag(Entry e, String name) {
	Optional<Field> f = fields.computeIfAbsent(e.getClass(), k -> new ConcurrentHashMap<>())
	    .computeIfAbsent(name, n -> {
		try {
		    return(Optional.of(e.getClass().getField(n)));
		} catch(NoSuchFieldException ex) {
		    return(Optional.empty());
		}
	    });
	if(!f.isPresent())
	    return(false);
	try {
	    Object v = f.get().get(e);
	    return((v instanceof Boolean) && (Boolean)v);
	} catch(IllegalAccessException ex) {
	    return(false);
	}
    }

    /** True when the Cattle Roster lists the animal as lactating. */
    public static boolean milkable(Gob gob) {
	CattleId cid = gob.getattr(CattleId.class);
	Entry e = (cid == null) ? null : cid.entry();
	if(e == null)
	    return(false);
	return(flag(e, "lactate") && !flag(e, "dead"));
    }

    private static final Text.Foundry fnd = new Text.Foundry(Text.dfont, 12);
    public static final Color MILK_COL = new Color(170, 215, 255);
    public static final Color WOOL_COL = new Color(255, 225, 140);
    private static final Map<Integer, Tex> labels = new ConcurrentHashMap<>();

    private static BufferedImage word(String s, Color c) {
	return(rasterimg(blurmask2(Utils.outline2(fnd.render(s, c).img, Color.BLACK, true).getRaster(), UI.rscale(1.0), UI.rscale(1.0), Color.BLACK)));
    }

    /** 1 = milk, 2 = wool, 3 = both. */
    private static Tex label(int kind) {
	return(labels.computeIfAbsent(kind, k -> {
	    if(k == 1) return(new TexI(word("Milk", MILK_COL)));
	    if(k == 2) return(new TexI(word("Wool", WOOL_COL)));
	    return(new TexI(ItemInfo.catimgsh(UI.scale(4), word("Milk", MILK_COL), word("Wool", WOOL_COL))));
	}));
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
		if(milkable(gob)) k |= 1;
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
	    g.aimage(label(k), sc.sub(0, up), 0.5, 1.0);
	}
    }
}
