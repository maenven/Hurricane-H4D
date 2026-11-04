package haven;

import java.awt.Color;

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
	    if(e.nextEnd < 0) {
		if(e.pct >= 0)
		    txt = String.format("%d%%", Math.round(e.pct * 100));
	    } else if(now >= e.allEnd) {
		txt = "Done";
		col = DONE;
	    } else if(now >= e.nextEnd) {
		txt = "Some done, all in " + ProcessTimers.fmt(e.allEnd - now);
		col = DONE;
	    } else if(e.allEnd - e.nextEnd >= 60) {
		txt = ProcessTimers.fmt(e.nextEnd - now) + " (all " + ProcessTimers.fmt(e.allEnd - now) + ")";
	    } else {
		txt = ProcessTimers.fmt(e.nextEnd - now);
	    }
	    if(txt != null && e.paused && col != DONE) {
		txt = txt + " (unlit)";
		col = PAUSED;
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
