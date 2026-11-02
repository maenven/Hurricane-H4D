package haven.automated.helpers;

import haven.*;

import java.util.function.IntConsumer;

/**
 * A client-side menu that looks like the game's flower menu (vertical layout), but whose choice
 * is handled locally instead of being sent to the server. Opens at the last click position.
 *
 * Choosing an option, pressing its number key, clicking outside or pressing Escape closes it.
 * The callback gets the chosen index, or nothing at all when the menu was dismissed.
 */
public class ClientMenu extends Widget {
	private final IntConsumer onChoose;
	private final Opt[] opts;
	private UI.Grab mg, kg;
	private boolean done = false;

	public ClientMenu(String[] options, IntConsumer onChoose) {
		super(Coord.z);
		this.onChoose = onChoose;
		opts = new Opt[options.length];
		int w = 0;
		for (int i = 0; i < options.length; i++) {
			opts[i] = new Opt(options[i], i);
			w = Math.max(w, opts[i].sz.x);
		}
		int y = UI.scale(3);
		for (Opt o : opts) {
			o.resize(w, o.sz.y);
			add(o, new Coord(UI.scale(3), y));
			y += o.sz.y;
		}
		resize(new Coord(w + UI.scale(6), y + UI.scale(3)));
	}

	protected void added() {
		if (c.equals(-1, -1))
			c = parent.ui.lcc;
		// Keep the menu on screen, like the vertical flower menu does.
		if (c.x + sz.x > parent.sz.x)
			c = c.add(parent.sz.x - (c.x + sz.x), 0);
		if (c.y + sz.y > parent.sz.y)
			c = c.add(0, parent.sz.y - (c.y + sz.y));
		mg = ui.grabmouse(this);
		kg = ui.grabkeys(this);
	}

	private void choose(int idx) {
		if (done)
			return;
		done = true;
		if (mg != null) mg.remove();
		if (kg != null) kg.remove();
		ui.destroy(this);
		if (idx >= 0)
			onChoose.accept(idx);
	}

	public boolean mousedown(MouseDownEvent ev) {
		if (!ev.propagate(this))
			choose(-1);
		return (true);
	}

	public boolean keydown(KeyDownEvent ev) {
		if ((ev.c >= '1') && (ev.c <= '9')) {
			int opt = ev.c - '1';
			if (opt < opts.length)
				choose(opt);
			return (true);
		} else if (key_esc.match(ev)) {
			choose(-1);
			return (true);
		}
		return (super.keydown(ev));
	}

	private class Opt extends Widget {
		private final int idx;
		private final Text text;

		Opt(String name, int idx) {
			super(Coord.z);
			this.idx = idx;
			text = FlowerMenu.ptf.renderstroked((idx + 1) + ". " + name, FlowerMenu.ptc, FlowerMenu.ptcStroke);
			resize(text.sz().x + UI.scale(25), FlowerMenu.ph);
		}

		public void draw(GOut g) {
			g.image(FlowerMenu.pbg, new Coord(3, 3), new Coord(3, 3), sz.add(new Coord(-6, -6)), UI.scale(FlowerMenu.pbg.sz()));
			FlowerMenu.pbox.draw(g, Coord.z, sz);
			g.image(text.tex(), sz.div(2).sub(text.sz().div(2)));
		}

		public boolean mousedown(MouseDownEvent ev) {
			choose(idx);
			return (true);
		}
	}
}
