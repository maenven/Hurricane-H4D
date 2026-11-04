/* Preprocessed source code */
package haven.res.ui.croster;

import haven.*;
import haven.render.*;
import java.util.*;
import java.util.function.*;
import haven.MenuGrid.Pagina;
import java.awt.Color;
import java.awt.image.BufferedImage;

@haven.FromResource(name = "ui/croster", version = 78)
public class RosterWindow extends Window {
    public static final Map<Glob, RosterWindow> rosters = new HashMap<>();
    public static int rmseq = 0;
    public int btny = 0;
    public List<TypeButton> buttons = new ArrayList<>();

    RosterWindow() {
	super(Coord.z, "Cattle Roster", true);
    }

    public void show(CattleRoster rost) {
	for(CattleRoster ch : children(CattleRoster.class))
	    ch.show(ch == rost);
    }

    public void addroster(CattleRoster rost) {
	if(btny == 0)
	    btny = rost.sz.y + UI.scale(10);
	add(rost, Coord.z);
	TypeButton btn = this.add(rost.button());
	btn.action(() -> show(rost));
	buttons.add(btn);
	buttons.sort((a, b) -> (a.order - b.order));
	int x = 0;
	for(Widget wdg : buttons) {
	    wdg.move(new Coord(x, btny));
	    x += wdg.sz.x + UI.scale(10);
	}
	buttons.get(0).click();
	placeAnimalStatsControls(); // H4D: AnimalStatsOverlay controls on the button row
	pack();
	rmseq++;
    }

    private haven.automated.AnimalStatsOverlay.ShowBox statsShow;
    private haven.automated.AnimalStatsOverlay.HighlightBox statsHighlight;
    private haven.automated.AnimalStatsOverlay.HeatmapBox statsHeatmap;
    private void placeAnimalStatsControls() {
	if(statsShow == null) {
	    statsShow = add(new haven.automated.AnimalStatsOverlay.ShowBox());
	    statsHighlight = add(new haven.automated.AnimalStatsOverlay.HighlightBox(UI.scale(190)));
	    statsHeatmap = add(new haven.automated.AnimalStatsOverlay.HeatmapBox());
	}
	int right = CattleRoster.WIDTH;
	/* Right edge: "Stats above animals" sits right above "Heatmap"; the dropdown stays left of Heatmap,
	 * where it was (clear of the type buttons on the left). */
	int row2 = btny + statsShow.sz.y + UI.scale(4);
	statsShow.move(new Coord(right - statsShow.sz.x, btny));
	statsHighlight.move(new Coord(right - statsHeatmap.sz.x - UI.scale(8) - statsHighlight.sz.x, row2));
	statsHeatmap.move(new Coord(right - statsHeatmap.sz.x, row2 + (statsHighlight.sz.y - statsHeatmap.sz.y) / 2));
    }

    public void reqclose() {
	hide();
    }
}

/* >pagina: RosterButton$Fac */
