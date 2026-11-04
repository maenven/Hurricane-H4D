package haven;

import haven.res.ui.stackinv.ItemStack;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * H4D: "Take #" row added under a barrel window's contents. Type an amount and press Take # (or Enter
 * in the box) to take exactly that many units of flour, salt, cave dust, seeds... instead of what the
 * stock Take button hands out in one go. See doc/H4D-features/barrel-take-amount.md.
 *
 * The server only knows the stock Take button, so the client presses it once per unit and waits for the
 * unit to show up in the inventory (or on the cursor). While Automatic Item Stacking is on, one Take hands
 * out a whole stack, so stacking is switched off for the run and switched back on afterwards.
 */
public class BarrelTakeAmount extends Widget {
    public static final String WINDOW_CAP = "Barrel";
    private static final String AMOUNT_PREF = "barrel-takecount";
    private static final String STACKING_BUFF = "itemcomb";
    private static final int IDLE = 0, UNSTACK = 1, SEND = 2, WAIT_ITEM = 3, PAUSE = 4;
    private static final double TOGGLE_TIMEOUT = 2.0, ITEM_TIMEOUT = 2.0, PAUSE_TIME = 0.1;

    private final CountEntry amount;
    private final Button btn;
    private GameUI gui;
    private int phase = IDLE, total = 0, got = 0, before = 0;
    private double ptime = 0;
    private boolean restoreStacking = false;

    public BarrelTakeAmount() {
	super(Coord.z);
	btn = add(new Button(UI.scale(80), label()), Coord.of(UI.scale(45), 0)).action(this::toggle);
	amount = add(new CountEntry(UI.scale(40), Integer.toString(savedAmount())), Coord.of(0, (btn.sz.y - TextEntry.mext.sz().y) / 2));
	btn.tooltip = RichText.render("Take exactly this many from the barrel, one unit at a time.\n" +
		"$col[218,163,0]{Automatic Item Stacking} is switched off while it runs (one Take would otherwise hand out a full stack) " +
		"and switched back on when it is done.", UI.scale(300));
	pack();
    }

    /** The barrel's own Take button, wherever the server put it in the window. */
    public static Button findTake(Widget wnd) {
	for(Widget w = wnd.child; w != null; w = w.next) {
	    if(w instanceof BarrelTakeAmount)
		continue; /* skip our own row */
	    if((w instanceof Button) && (((Button)w).text != null) && "Take".equals(((Button)w).text.text))
		return((Button)w);
	    Button b = findTake(w);
	    if(b != null)
		return(b);
	}
	return(null);
    }

    private static int savedAmount() {
	return(Math.max(1, Utils.getprefi(AMOUNT_PREF, 1)));
    }

    private String label() {
	if(phase == IDLE)
	    return("Take #");
	return(String.format("Stop %d/%d", got, total));
    }

    private void relabel() {
	btn.change(label());
    }

    private void toggle() {
	if(phase != IDLE)
	    finish(String.format("Stopped: took %d of %d.", got, total));
	else
	    start();
    }

    private void start() {
	int n = amount.value();
	if(n <= 0) {
	    ui.error("Enter how many to take.");
	    return;
	}
	gui = getparent(GameUI.class);
	if((gui == null) || (gui.maininv == null))
	    return;
	Utils.setprefi(AMOUNT_PREF, n);
	total = n;
	got = 0;
	ptime = 0;
	restoreStacking = false;
	if(stackingOn()) {
	    gui.wdgmsg("act", STACKING_BUFF);
	    restoreStacking = true;
	    phase = UNSTACK;
	} else {
	    phase = SEND;
	}
	relabel();
    }

    private void finish(String msg) {
	if(phase == IDLE)
	    return;
	phase = IDLE;
	restore();
	relabel();
	if((msg != null) && (ui != null))
	    ui.msg(msg);
    }

    private void restore() {
	if(restoreStacking && (gui != null)) {
	    restoreStacking = false;
	    if(!stackingOn())
		gui.wdgmsg("act", STACKING_BUFF);
	}
    }

    private boolean stackingOn() {
	return((gui != null) && (gui.buffs != null) && (gui.buffs.gettoggle(STACKING_BUFF) != null));
    }

    /** Units in the main inventory plus the cursor; a stack counts as the items in it. */
    private int units() {
	int n = 0;
	for(WItem w : gui.maininv.getAllItems())
	    n += units(w);
	if(gui.vhand != null)
	    n += units(gui.vhand);
	return(n);
    }

    private static int units(WItem w) {
	Widget cont = w.item.contents;
	if(cont instanceof ItemStack)
	    return(Math.max(1, ((ItemStack)cont).order.size()));
	return(1);
    }

    public void tick(double dt) {
	super.tick(dt);
	if(phase == IDLE)
	    return;
	if((gui == null) || (gui.maininv == null) || (parent == null)) {
	    finish(null);
	    return;
	}
	ptime += dt;
	switch(phase) {
	case UNSTACK:
	    /* Wait for the server to confirm stacking is off; go on anyway after a while. */
	    if(!stackingOn() || (ptime > TOGGLE_TIMEOUT)) {
		phase = SEND;
		ptime = 0;
	    }
	    break;
	case SEND: {
	    Button take = findTake(parent);
	    if(take == null) {
		finish(String.format("Took %d of %d (the barrel is empty).", got, total));
		break;
	    }
	    before = units();
	    take.click();
	    phase = WAIT_ITEM;
	    ptime = 0;
	    break;
	}
	case WAIT_ITEM: {
	    int now = units();
	    if(now > before) {
		got += now - before;
		if(got >= total) {
		    finish((got == total) ? String.format("Took %d.", got) :
			   String.format("Took %d (wanted %d: the barrel hands out more than one at a time).", got, total));
		} else {
		    phase = PAUSE;
		    ptime = 0;
		    relabel();
		}
	    } else if(ptime > ITEM_TIMEOUT) {
		finish(String.format("Took %d of %d (barrel empty or no room left).", got, total));
	    }
	    break;
	}
	case PAUSE:
	    if(ptime >= PAUSE_TIME)
		phase = SEND;
	    break;
	}
    }

    public void dispose() {
	/* Window closed mid-run: never leave the player's stacking switched off. */
	restore();
	phase = IDLE;
	super.dispose();
    }

    /* Digits only; Enter starts the run. */
    private class CountEntry extends TextEntry {
	private final Set<Integer> allowed = new HashSet<>(Arrays.asList(
	    KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT, KeyEvent.VK_HOME, KeyEvent.VK_END,
	    KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE));

	CountEntry(int w, String deftext) {
	    super(w, deftext);
	    autofocus = false;
	}

	public boolean keydown(KeyDownEvent ev) {
	    char ch = ev.awt.getKeyChar();
	    if(((ch >= '0') && (ch <= '9')) || allowed.contains(ev.awt.getKeyCode())) {
		if(Character.isDigit(ch) && (text().length() >= 3) && (buf.mark() < 0))
		    return(true);
		return(super.keydown(ev));
	    }
	    return(false);
	}

	public void done(ReadLine buf) {
	    if(phase == IDLE)
		start();
	}

	int value() {
	    try {
		return(Integer.parseInt(text().trim()));
	    } catch(NumberFormatException e) {
		return(-1);
	    }
	}
    }
}
