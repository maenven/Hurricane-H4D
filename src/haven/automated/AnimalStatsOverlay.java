package haven.automated;

import haven.*;
import haven.render.Pipe;
import haven.res.ui.croster.CattleId;
import haven.res.ui.croster.CattleRoster;
import haven.res.ui.croster.Entry;
import haven.res.ui.pag.toggle.Toggle;

import java.awt.Color;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H4D: shows the stats of memorized (Cattle Roster) animals above their heads, and optionally highlights
 * the animal(s) with the best value of one chosen stat within their roster (species) in green.
 *
 * The per-species roster entry classes (Ochs, Goat, Sheep, Horse, Pig, Teimdeer) are server resource code,
 * so their stat fields (seedq, milkq, meatq, hideq, woolq) are read by reflection. Entries only exist once the
 * Cattle Roster has been opened this session (the server sends the list then).
 */
public class AnimalStatsOverlay {
    public static final String PREF_ENABLED = "animalStatsOverhead";
    public static final String PREF_HIGHLIGHT = "animalStatsHighlight";

    /** A stat that can be shown/highlighted. {@code field} is the roster entry field name. */
    public static class Stat {
	public final String field, label, menuName;
	public final boolean percent;
	Stat(String field, String label, String menuName, boolean percent) {
	    this.field = field; this.label = label; this.menuName = menuName; this.percent = percent;
	}
    }

    public static final Stat NONE = new Stat(null, "", "No highlight", false);
    public static final Stat QUALITY = new Stat("q", "Q", "Quality", false);
    public static final Stat BREED = new Stat("seedq", "B", "Breeding quality", false);
    public static final Stat MILK = new Stat("milkq", "M", "Milk quality", true);
    public static final Stat MEAT = new Stat("meatq", "Mt", "Meat quality", true);
    public static final Stat HIDE = new Stat("hideq", "H", "Hide quality", true);
    public static final Stat WOOL = new Stat("woolq", "W", "Wool quality", true);
    public static final List<Stat> HIGHLIGHTS = Arrays.asList(NONE, QUALITY, BREED, MILK, MEAT, HIDE, WOOL);

    private static volatile boolean enabled = Utils.getprefb(PREF_ENABLED, false);
    private static volatile Stat highlight = byField(Utils.getpref(PREF_HIGHLIGHT, "none"));
    /** Ids of the animals that currently have the best value of {@link #highlight}; computed on the UI thread. */
    private static volatile Set<UID> best = Collections.emptySet();
    private static final Set<Gob> highlighted = new HashSet<>();
    private static double sinceUpdate = 0;

    public static boolean enabled() {return(enabled);}
    public static Stat highlight() {return(highlight);}

    public static void setEnabled(boolean val, UI ui) {
	enabled = val;
	Utils.setprefb(PREF_ENABLED, val);
	sinceUpdate = 1;
	if(ui != null && ui.gui != null)
	    ui.gui.optionInfoMsg("Animal Stats Above Heads is now " + (val ? "ENABLED" : "DISABLED") + ".", (val ? OptWnd.msgGreen : OptWnd.msgRed), Audio.resclip(val ? Toggle.sfxon : Toggle.sfxoff));
    }

    public static void toggle(UI ui) {setEnabled(!enabled, ui);}

    public static void setHighlight(Stat stat) {
	highlight = (stat == null) ? NONE : stat;
	Utils.setpref(PREF_HIGHLIGHT, (highlight.field == null) ? "none" : highlight.field);
	sinceUpdate = 1;
    }

    private static Stat byField(String field) {
	for(Stat s : HIGHLIGHTS) {
	    if(Objects.equals(s.field, field))
		return(s);
	}
	return(NONE);
    }

    private static final Map<Class<?>, Map<String, Optional<Field>>> fields = new ConcurrentHashMap<>();

    /** The value of a stat for a roster entry, or null if that animal type has no such stat. */
    public static Double value(Entry e, Stat stat) {
	if(e == null || stat == null || stat.field == null)
	    return(null);
	if(stat == QUALITY)
	    return(e.q);
	Optional<Field> f = fields.computeIfAbsent(e.getClass(), k -> new ConcurrentHashMap<>())
	    .computeIfAbsent(stat.field, n -> {
		try {
		    Field fl = e.getClass().getField(n);
		    return(Optional.of(fl));
		} catch(NoSuchFieldException ex) {
		    return(Optional.empty());
		}
	    });
	if(!f.isPresent())
	    return(null);
	try {
	    Object v = f.get().get(e);
	    return((v instanceof Number) ? ((Number)v).doubleValue() : null);
	} catch(IllegalAccessException ex) {
	    return(null);
	}
    }

    private static boolean hasMilk(Entry e) {
	String n = e.getClass().getSimpleName();
	return(!n.equals("Horse") && !n.equals("Pig"));
    }

    private static String fmt(Stat s, double v) {
	return(s.label + " " + Math.round(v) + (s.percent ? "%" : ""));
    }

    /** The text shown above an animal, e.g. "Q 45 | B 30 | M 120%". */
    public static String text(Entry e) {
	StringBuilder sb = new StringBuilder();
	List<Stat> shown = new ArrayList<>(Arrays.asList(QUALITY, BREED));
	if(hasMilk(e))
	    shown.add(MILK);
	Stat hl = highlight;
	if(hl.field != null && !shown.contains(hl))
	    shown.add(hl);
	for(Stat s : shown) {
	    Double v = value(e, s);
	    if(v == null)
		continue;
	    if(sb.length() > 0)
		sb.append(" | ");
	    sb.append(fmt(s, v));
	}
	return(sb.toString());
    }

    public static boolean isBest(Entry e) {
	return((e != null) && best.contains(e.id));
    }

    /** Called from GameUI.tick (UI thread). Recomputes the best animals and updates the green gob tint. */
    public static void tick(GameUI gui, double dt) {
	sinceUpdate += dt;
	if(sinceUpdate < 0.5)
	    return;
	sinceUpdate = 0;
	Stat hl = highlight;
	boolean active = enabled && hl.field != null && gui.map != null;
	Set<Gob> want = new HashSet<>();
	if(active) {
	    List<Gob> gobs = new ArrayList<>();
	    OCache oc = gui.map.glob.oc;
	    synchronized(oc) {
		for(Gob gob : oc)
		    gobs.add(gob);
	    }
	    Set<CattleRoster<?>> rosters = new HashSet<>();
	    Map<Gob, Entry> known = new HashMap<>();
	    for(Gob gob : gobs) {
		CattleId cid = gob.getattr(CattleId.class);
		if(cid == null)
		    continue;
		Entry e = cid.entry();
		if(e == null)
		    continue;
		known.put(gob, e);
		if(cid.roster() != null)
		    rosters.add(cid.roster());
	    }
	    Set<UID> nbest = new HashSet<>();
	    for(CattleRoster<?> r : rosters) {
		double max = Double.NEGATIVE_INFINITY;
		int n = 0;
		for(Entry e : r.entries.values()) {
		    Double v = value(e, hl);
		    if(v == null)
			continue;
		    n++;
		    max = Math.max(max, v);
		}
		if(n < 2)
		    continue;
		for(Entry e : r.entries.values()) {
		    Double v = value(e, hl);
		    if(v != null && v == max)
			nbest.add(e.id);
		}
	    }
	    best = nbest;
	    for(Map.Entry<Gob, Entry> ke : known.entrySet()) {
		if(nbest.contains(ke.getValue().id))
		    want.add(ke.getKey());
	    }
	} else {
	    best = Collections.emptySet();
	}
	for(Iterator<Gob> i = highlighted.iterator(); i.hasNext();) {
	    Gob gob = i.next();
	    if(!want.contains(gob)) {
		synchronized(gob) {
		    gob.delattr(BestHighlight.class);
		}
		i.remove();
	    }
	}
	for(Gob gob : want) {
	    if(highlighted.add(gob)) {
		synchronized(gob) {
		    gob.setattr(new BestHighlight(gob));
		}
	    }
	}
    }

    public static final Color BEST_TEXT = new Color(80, 255, 80);
    private static final ColorMask BEST_TINT = new ColorMask(new Color(0, 220, 0, 130));

    public static class BestHighlight extends GAttrib implements Gob.SetupMod {
	public BestHighlight(Gob g) {super(g);}
	public Pipe.Op gobstate() {return(BEST_TINT);}
    }

    /** Dropdown for the Cattle Roster window: which stat to highlight the best animal by. */
    public static class HighlightBox extends OldDropBox<Stat> {
	public HighlightBox(int w) {
	    super(w, HIGHLIGHTS.size(), UI.scale(18));
	    sel = highlight;
	}
	protected Stat listitem(int i) {return(HIGHLIGHTS.get(i));}
	protected int listitems() {return(HIGHLIGHTS.size());}
	protected void drawitem(GOut g, Stat item, int i) {
	    g.chcolor((item.field == null) ? Color.WHITE : BEST_TEXT);
	    g.text((item.field == null) ? item.menuName : ("Best " + item.menuName), Coord.of(UI.scale(3), (itemh - Text.std.height()) / 2));
	    g.chcolor();
	}
	public void change(Stat item) {
	    super.change(item);
	    setHighlight(item);
	}
	public void tick(double dt) {
	    super.tick(dt);
	    if(sel != highlight)
		sel = highlight;
	}
	public Object tooltip(Coord c, Widget prev) {
	    return("Highlight (green) the animal with the highest value of this stat among the memorized animals of its kind.\nOnly applies while stats above heads are shown.");
	}
    }

    /** Checkbox for the Cattle Roster window, kept in sync with the toggle button. */
    public static class ShowBox extends CheckBox {
	public ShowBox() {
	    super("Show stats above animals");
	    a = enabled;
	}
	public void set(boolean val) {
	    setEnabled(val, ui);
	    a = val;
	}
	public void tick(double dt) {
	    super.tick(dt);
	    a = enabled;
	}
	public Object tooltip(Coord c, Widget prev) {
	    return("Show Quality | Breeding quality | Milk quality above every memorized animal, even with this window closed.\nAlso available as a toggle button: Custom Client Extras > Toggles > Animal Stats Above Heads.");
	}
    }
}
