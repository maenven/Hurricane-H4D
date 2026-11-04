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
 * the animal(s) with the best value of one chosen stat within their roster (species) in green, or color all of
 * them as a heatmap (green highest, yellow, red lowest).
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
    public static final Stat MILK = new Stat("milkq", "MQ", "Milk quality", true);
    public static final Stat MEAT = new Stat("meatq", "MeatQ", "Meat quality", true);
    public static final Stat HIDE = new Stat("hideq", "HideQ", "Hide quality", true);
    public static final Stat WOOL = new Stat("woolq", "WoolQ", "Wool quality", true);
    /* Amounts (quantity) - in game these matter more than the quality percentages. */
    public static final Stat MILK_AMT = new Stat("milk", "Milk", "Milk amount", false);
    public static final Stat MEAT_AMT = new Stat("meat", "Meat", "Meat amount", false);
    public static final Stat WOOL_AMT = new Stat("wool", "Wool", "Wool amount", false);
    public static final List<Stat> HIGHLIGHTS = Arrays.asList(NONE, MILK_AMT, MEAT_AMT, WOOL_AMT, QUALITY, BREED, MILK, MEAT, HIDE, WOOL);

    private static volatile boolean enabled = Utils.getprefb(PREF_ENABLED, false);
    public static final String PREF_HEATMAP = "animalStatsHeatmap";
    private static volatile Stat highlight = byField(Utils.getpref(PREF_HIGHLIGHT, "none"));
    private static volatile boolean heatmap = Utils.getprefb(PREF_HEATMAP, false);
    /** Highlight color per animal id for {@link #highlight}; computed on the UI thread. */
    private static volatile Map<UID, Color> colors = Collections.emptyMap();
    private static final Map<Gob, Color> highlighted = new HashMap<>();
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

    public static boolean heatmap() {return(heatmap);}
    public static void setHeatmap(boolean val) {
	heatmap = val;
	Utils.setprefb(PREF_HEATMAP, val);
	sinceUpdate = 1;
    }

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

    private static void line(StringBuilder sb, Entry e, List<Stat> stats) {
	StringBuilder ln = new StringBuilder();
	for(Stat st : stats) {
	    Double v = value(e, st);
	    if(v == null)
		continue;
	    if(ln.length() > 0)
		ln.append(" | ");
	    ln.append(fmt(st, v));
	}
	if(ln.length() > 0) {
	    if(sb.length() > 0)
		sb.append('\n');
	    sb.append(ln);
	}
    }

    /** The text shown above an animal: amounts on the top line, qualities below, e.g.
     * "Milk 40 | Meat 30" / "Q 45 | B 30 | MQ 120%". Lines are separated by '\n'. */
    public static String text(Entry e) {
	Stat hl = highlight;
	List<Stat> amounts = new ArrayList<>();
	if(hasMilk(e))
	    amounts.add(MILK_AMT);
	amounts.add(MEAT_AMT);
	amounts.add(WOOL_AMT);
	List<Stat> quals = new ArrayList<>(Arrays.asList(QUALITY, BREED));
	if(hasMilk(e))
	    quals.add(MILK);
	if(hl.field != null && !amounts.contains(hl) && !quals.contains(hl))
	    quals.add(hl);
	StringBuilder sb = new StringBuilder();
	line(sb, e, amounts);
	line(sb, e, quals);
	return(sb.toString());
    }

    /** Highlight color of an animal, or null when it isn't highlighted. */
    public static Color colorOf(Entry e) {
	return((e == null) ? null : colors.get(e.id));
    }

    /** Heatmap color: t=1 green, 0.5 yellow, 0 red; quantized to 10 steps so textures/masks get reused. */
    public static Color heat(double t) {
	t = Math.round(Math.max(0, Math.min(1, t)) * 10) / 10.0;
	int r = (t < 0.5) ? 255 : (int)Math.round(255 * (1 - t) * 2);
	int g = (t > 0.5) ? 255 : (int)Math.round(255 * t * 2);
	return(new Color(Math.max(r, 40), Math.max(g, 40), 40));
    }

    /** Called from GameUI.tick (UI thread). Recomputes the best animals and updates the green gob tint. */
    public static void tick(GameUI gui, double dt) {
	sinceUpdate += dt;
	if(sinceUpdate < 0.5)
	    return;
	sinceUpdate = 0;
	Stat hl = highlight;
	boolean active = enabled && hl.field != null && gui.map != null;
	Map<Gob, Color> want = new HashMap<>();
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
	    Map<UID, Color> ncol = new HashMap<>();
	    boolean hm = heatmap;
	    for(CattleRoster<?> r : rosters) {
		double max = Double.NEGATIVE_INFINITY, min = Double.POSITIVE_INFINITY;
		int n = 0;
		for(Entry e : r.entries.values()) {
		    Double v = value(e, hl);
		    if(v == null)
			continue;
		    n++;
		    max = Math.max(max, v);
		    min = Math.min(min, v);
		}
		if(n < 2)
		    continue;
		for(Entry e : r.entries.values()) {
		    Double v = value(e, hl);
		    if(v == null)
			continue;
		    if(hm)
			ncol.put(e.id, heat((max > min) ? (v - min) / (max - min) : 1));
		    else if(v == max)
			ncol.put(e.id, BEST_TEXT);
		}
	    }
	    colors = ncol;
	    for(Map.Entry<Gob, Entry> ke : known.entrySet()) {
		Color c = ncol.get(ke.getValue().id);
		if(c != null)
		    want.put(ke.getKey(), c);
	    }
	} else {
	    colors = Collections.emptyMap();
	}
	for(Iterator<Map.Entry<Gob, Color>> i = highlighted.entrySet().iterator(); i.hasNext();) {
	    Map.Entry<Gob, Color> he = i.next();
	    if(!want.containsKey(he.getKey())) {
		synchronized(he.getKey()) {
		    he.getKey().delattr(BestHighlight.class);
		}
		i.remove();
	    }
	}
	for(Map.Entry<Gob, Color> we : want.entrySet()) {
	    if(!we.getValue().equals(highlighted.get(we.getKey()))) {
		highlighted.put(we.getKey(), we.getValue());
		synchronized(we.getKey()) {
		    we.getKey().setattr(new BestHighlight(we.getKey(), we.getValue()));
		}
	    }
	}
    }

    public static final Color BEST_TEXT = new Color(80, 255, 80);
    private static final Map<Color, ColorMask> tints = new ConcurrentHashMap<>();

    public static class BestHighlight extends GAttrib implements Gob.SetupMod {
	private final ColorMask tint;
	public BestHighlight(Gob g, Color c) {
	    super(g);
	    tint = tints.computeIfAbsent(c, k -> new ColorMask(new Color(k.getRed(), k.getGreen(), k.getBlue(), 130)));
	}
	public Pipe.Op gobstate() {return(tint);}
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
	    g.text((item.field == null) ? item.menuName : ((heatmap ? "Heatmap: " : "Best ") + item.menuName), Coord.of(UI.scale(3), (itemh - Text.std.height()) / 2));
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
	    if(heatmap)
		return("Color every animal by this stat among the memorized animals of its kind: highest green, middle yellow, lowest red.\nOnly applies while stats above heads are shown.");
	    return("Highlight (green) the animal with the highest value of this stat among the memorized animals of its kind.\nOnly applies while stats above heads are shown.");
	}
    }

    /** "Heatmap" checkbox for the Cattle Roster window. */
    public static class HeatmapBox extends CheckBox {
	public HeatmapBox() {
	    super("Heatmap");
	    a = heatmap;
	}
	public void set(boolean val) {
	    setHeatmap(val);
	    a = val;
	}
	public Object tooltip(Coord c, Widget prev) {
	    return("Off: only the best animal is highlighted green.\nOn: every animal is colored from green (highest) through yellow to red (lowest) by the selected stat.");
	}
    }

    /** Checkbox for the Cattle Roster window, kept in sync with the toggle button. */
    public static class ShowBox extends CheckBox {
	public ShowBox() {
	    super("Stats above animals");
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
	    return("Show milk/meat/wool amounts and Quality | Breeding quality | Milk quality above every memorized animal, even with this window closed.\nAlso available as a toggle button: Custom Client Extras > Toggles > Animal Stats Above Heads.");
	}
    }
}
