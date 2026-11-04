package haven;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** H4D: draws the {@link ProcessTimers} countdown above a smelter, oven, kiln or crucible. */
public class ProcessTimerGobInfo extends GobInfo {
    private static final Color RUNNING = new Color(255, 255, 255);
    private static final Color DONE = new Color(120, 255, 120);
    private static final Color PAUSED = new Color(190, 190, 190);
    private String shown = null;
    private Color shownCol = RUNNING;

    protected ProcessTimerGobInfo(Gob owner) {
	super(owner);
	center = new Pair<>(0.5, 1.0);
    }

    @Override
    protected boolean enabled() {
	return ProcessTimers.enabled() && shown != null;
    }

    @Override
    public void ctick(double dt) {
	String txt = null;
	Color col = RUNNING;
	ProcessTimers.Entry e = ProcessTimers.enabled() ? ProcessTimers.get(gob.id) : null;
	if(e != null) {
	    double now = Utils.rtime();
	    List<String> parts = new ArrayList<>();
	    boolean done = false;
	    if(e.nextEnd >= 0) {
		if(now >= e.allEnd) {
		    parts.add("Done"); done = true;
		} else if(now >= e.nextEnd) {
		    parts.add("Some done, all in " + ProcessTimers.fmt(e.allEnd - now)); done = true;
		} else if(e.allEnd - e.nextEnd >= 60) {
		    parts.add(ProcessTimers.fmt(e.nextEnd - now) + " (all " + ProcessTimers.fmt(e.allEnd - now) + ")");
		} else {
		    parts.add(ProcessTimers.fmt(e.nextEnd - now));
		}
	    } else if(e.pct >= 0) {
		parts.add(String.format("%d%%", Math.round(e.pct * 100)));
	    }
	    if(e.bakeEnd >= 0) {
		if(now >= e.bakeEnd) {parts.add("Baked"); done = true;}
		else parts.add("Baked in " + ProcessTimers.fmt(e.bakeEnd - now));
	    }
	    if(e.fireEnd >= 0)
		parts.add((now >= e.fireEnd) ? "Fire out" : "Fire " + ProcessTimers.fmt(e.fireEnd - now));
	    if(!parts.isEmpty()) {
		txt = String.join(" \u00b7 ", parts);
		if(e.paused && !(now >= e.fireEnd && e.fireEnd >= 0)) {
		    txt = txt + " (unlit)";
		    col = PAUSED;
		} else if(done) {
		    col = DONE;
		}
	    }
	}
	if(!java.util.Objects.equals(txt, shown) || col != shownCol) {
	    shown = txt;
	    shownCol = col;
	    clear();
	}
	super.ctick(dt);
    }

    @Override
    protected Tex render() {
	String txt = shown;
	if(txt == null) return null;
	up(12);
	return new TexI(PUtils.strokeImg(Text.std.renderstroked(txt, shownCol, Color.black).img));
    }
}
