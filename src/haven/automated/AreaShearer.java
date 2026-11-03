package haven.automated;

import haven.*;

import java.awt.Color;
import java.util.*;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Menu-grid button (Other Scripts & Tools): drag a rectangle over the sheep and goats, and every
 * one of them that has wool gets sheared. Before starting it puts shears in hand (the best ones you
 * have), or, without shears, your highest quality axe.
 *
 * The shearing itself follows Nurgling2's {@code ShearWool}: right-click the animal, pick
 * "Shear wool" from its flower menu, wait while the character works. Domestic sheep/goats only
 * (names containing "wild" are skipped, as in Nurgling). The server walks the character to each
 * animal; animals are done nearest first, each one once.
 */
public class AreaShearer implements Runnable {
	private static final String[] ANIMALS = {"gfx/kritter/sheep", "gfx/kritter/goat"};
	private static final String SHEARS = "shears";
	/** Basenames of every axe that can stand in for shears (pickaxe deliberately not included). */
	private static final Set<String> AXES = new HashSet<>(Arrays.asList(
			"axe-m", "stoneaxe", "woodsmansaxe", "tinkersthrowingaxe", "b12axe"));
	private static final int HAND_LEFT = 6, HAND_RIGHT = 7;

	/** How long one animal may take (walk + shear) before it is given up on. */
	private static final int SHEAR_TIMEOUT = 60000;

	private static volatile Thread running = null;

	private final GameUI gui;
	private final Coord2d ul, br;

	private AreaShearer(GameUI gui, Coord2d ul, Coord2d br) {
		this.gui = gui;
		this.ul = ul;
		this.br = br;
	}

	// ---------------------------------------------------------------------------------------
	// UI thread
	// ---------------------------------------------------------------------------------------

	/** The "Shear Animals in Area" button. Pressing it while a run is going stops the run. */
	public static void menuButton(GameUI gui) {
		if (gui == null || gui.map == null)
			return;
		Thread t = running;
		if (t != null && t.isAlive()) {
			t.interrupt();
			running = null;
			gui.msg("Stopped shearing.", Color.WHITE);
			return;
		}
		if (gui.vhand != null) {
			gui.error("Put down the item on your cursor first.");
			return;
		}
		boolean started = gui.map.selectAreaClientSide((a, b) -> areaSelected(gui, a, b));
		if (started)
			gui.msg("Shear: drag a rectangle over the sheep and goats. Right-click cancels.", Color.WHITE);
		else
			gui.error("Another area selection is already active.");
	}

	private static void areaSelected(GameUI gui, Coord tul, Coord tbr) {
		Coord2d ul = new Coord2d(tul.x * MCache.tilesz.x, tul.y * MCache.tilesz.y);
		Coord2d br = new Coord2d(tbr.x * MCache.tilesz.x, tbr.y * MCache.tilesz.y);
		AreaShearer bot = new AreaShearer(gui, ul, br);
		int n = bot.targets(Collections.emptySet()).size();
		if (n == 0) {
			gui.error("No sheep or goats in that area.");
			return;
		}
		Thread t = new Thread(bot, "AreaShearer");
		running = t;
		gui.msg("Shearing " + n + " animal" + (n == 1 ? "" : "s") + ". Press the Shear button again to stop.", Color.WHITE);
		t.start();
	}

	// ---------------------------------------------------------------------------------------
	// Bot thread
	// ---------------------------------------------------------------------------------------

	@Override
	public void run() {
		try {
			Gob player = gui.map.player();
			if (player == null)
				return;
			if (player.getattr(Following.class) != null) {
				gui.error("Get off the vehicle first.");
				return;
			}
			if (!equipTool())
				return;

			Set<Long> done = new HashSet<>();
			int sheared = 0, skipped = 0;
			boolean full = false;
			while (!Thread.currentThread().isInterrupted()) {
				if (gui.maininv != null && gui.maininv.getFreeSpace() < 2) {
					full = true;
					break;
				}
				Gob t = nearest(targets(done));
				if (t == null)
					break;
				done.add(t.id);
				if (shear(t))
					sheared++;
				else
					skipped++;
			}
			if (Thread.currentThread().isInterrupted())
				return;
			String msg = "Done: sheared " + sheared + " animal" + (sheared == 1 ? "" : "s");
			if (skipped > 0)
				msg += ", " + skipped + " had no wool or couldn't be reached";
			if (full)
				msg += ". Stopped: your inventory is full";
			gui.msg(msg + ".", Color.WHITE);
		} catch (InterruptedException ignored) {
		} catch (Exception e) {
			gui.error("Shearing stopped: " + e.getMessage());
		} finally {
			if (running == Thread.currentThread())
				running = null;
		}
	}

	/**
	 * Right-clicks the animal (the server walks there), picks "Shear wool" when its flower menu
	 * opens, then waits for the work to finish. False when the animal had no wool, the menu never
	 * came up, or the character never started working.
	 */
	private boolean shear(Gob target) throws InterruptedException {
		Gob g = gob(target.id);
		if (g == null)
			return false;
		clickGob(g, 3, 0);

		long start = System.currentTimeMillis();
		long stall = stallMs(3000);
		long idleSince = -1;
		FlowerMenu fm = null;
		while (fm == null) {
			fm = openFlowerMenu();
			if (fm != null)
				break;
			long now = System.currentTimeMillis();
			if (now - start > SHEAR_TIMEOUT)
				return false;
			Gob player = gui.map.player();
			if (player != null && player.getv() > 0)
				idleSince = -1;
			else if (idleSince < 0)
				idleSince = now;
			else if (now - idleSince > stall)
				return false; // stood still and no menu: couldn't reach it
			Thread.sleep(50);
		}
		String opt = null;
		for (String o : fm.options) {
			if (o.toLowerCase().startsWith("shear")) {
				opt = o;
				break;
			}
		}
		if (opt == null) {
			fm.choose(null); // no wool on this one
			waitFor(() -> openFlowerMenu() == null, 2000);
			return false;
		}
		fm.choose(fm.getPetalFromName(opt));

		// Wait for the work to start, then for it to end.
		boolean worked = false;
		idleSince = -1;
		while (true) {
			long now = System.currentTimeMillis();
			if (now - start > SHEAR_TIMEOUT)
				return worked;
			boolean busy = isWorking();
			Gob player = gui.map.player();
			if (busy)
				worked = true;
			if (busy || (player != null && player.getv() > 0))
				idleSince = -1;
			else if (idleSince < 0)
				idleSince = now;
			else if (now - idleSince > stall)
				return worked;
			Thread.sleep(50);
		}
	}

	private boolean isWorking() {
		if (gui.prog != null)
			return true;
		Gob player = gui.map.player();
		if (player == null)
			return false;
		for (String p : player.getPoses()) {
			if (p.contains("carving"))
				return true;
		}
		return false;
	}

	/** Domestic sheep and goats in the area that haven't been tried yet. */
	private List<Gob> targets(Set<Long> done) {
		List<Gob> res = new ArrayList<>();
		for (Gob g : Utils.getAllGobs(gui)) {
			if (done.contains(g.id) || !inArea(g.rc))
				continue;
			String n = resName(g);
			if (n == null || n.contains("wild"))
				continue;
			for (String a : ANIMALS) {
				if (n.startsWith(a)) {
					res.add(g);
					break;
				}
			}
		}
		return res;
	}

	// ---------------------------------------------------------------------------------------
	// Tool
	// ---------------------------------------------------------------------------------------

	private static boolean isShears(WItem w) {
		String b = basename(w);
		return b != null && b.equals(SHEARS);
	}

	private static boolean isAxe(WItem w) {
		String b = basename(w);
		return b != null && AXES.contains(b);
	}

	/**
	 * Puts shears in hand (any shears already held count; otherwise the best shears carried), or
	 * with no shears anywhere the highest quality axe (held or carried). False only when the swap
	 * failed; no tool at all is reported and shearing is still tried.
	 */
	private boolean equipTool() throws InterruptedException {
		Equipory eq = gui.getequipory();
		if (eq == null) {
			gui.error("Couldn't find your equipment.");
			return false;
		}
		WItem[] hands = {eq.slots[HAND_LEFT], eq.slots[HAND_RIGHT]};
		for (WItem h : hands) {
			if (h != null && isShears(h))
				return true;
		}
		List<WItem> carried = carriedItems();
		WItem best = best(carried, AreaShearer::isShears);
		String what = "shears";
		if (best == null) {
			WItem bestHeld = best(Arrays.asList(hands), AreaShearer::isAxe);
			best = best(carried, AreaShearer::isAxe);
			what = "axe";
			if (best == null || (bestHeld != null && quality(bestHeld) >= quality(best))) {
				if (bestHeld == null)
					gui.msg("No shears or axe found; trying to shear without one.", Color.WHITE);
				return true;
			}
		}
		if (!equip(eq, best)) {
			gui.error("Couldn't put the " + what + " in your hand.");
			return false;
		}
		gui.msg("Equipped " + best.item.getname() + " (q" + Math.round(quality(best)) + ").", Color.WHITE);
		return true;
	}

	/**
	 * Moves {@code item} into a hand. If both hands are full, the left-hand item goes into the
	 * inventory first; whatever ends up on the cursor after the swap (e.g. a two-hander) is put
	 * back where the tool came from or anywhere there's room.
	 */
	private boolean equip(Equipory eq, WItem item) throws InterruptedException {
		if (!(item.parent instanceof Inventory))
			return false;
		Inventory src = (Inventory) item.parent;
		Coord orig = item.c.sub(1, 1).div(Inventory.sqsz);
		String res = item.item.getres().name;

		WItem l = eq.slots[HAND_LEFT], r = eq.slots[HAND_RIGHT];
		int slot;
		if (l == null)
			slot = HAND_LEFT;
		else if (r == null)
			slot = HAND_RIGHT;
		else {
			slot = HAND_LEFT;
			if (l.item != r.item) {
				// Two different items in hand: make room first.
				l.item.wdgmsg("take", Coord.z);
				if (!waitFor(() -> gui.vhand != null, 3000))
					return false;
				if (!stashCursor(src, null))
					return false;
				// The tool's widget may have been replaced while the inventory changed.
				item = findCarried(res, orig, src);
				if (item == null)
					return false;
			}
		}

		item.item.wdgmsg("take", Coord.z);
		if (!waitFor(() -> gui.vhand != null, 3000))
			return false;
		eq.wdgmsg("drop", slot);
		if (!waitFor(() -> handHolds(eq, res), 3000))
			return false;
		// A swapped-out hand item lands on the cursor.
		waitFor(() -> gui.vhand != null, 500);
		if (gui.vhand != null)
			return stashCursor(src, orig);
		return true;
	}

	/** Drops the cursor item into {@code pref} at {@code at} (if given), else anywhere there's room. */
	private boolean stashCursor(Inventory pref, Coord at) throws InterruptedException {
		WItem v = gui.vhand;
		if (v == null)
			return true;
		Coord sz = v.sz.div(Inventory.sqsz);
		int w = Math.max(1, sz.x), h = Math.max(1, sz.y);
		List<Inventory> invs = new ArrayList<>();
		if (pref != null)
			invs.add(pref);
		if (gui.maininv != null && gui.maininv != pref)
			invs.add(gui.maininv);
		for (Inventory inv : ownInventories())
			if (!invs.contains(inv))
				invs.add(inv);
		if (at != null && pref != null) {
			pref.wdgmsg("drop", at);
			if (waitFor(() -> gui.vhand == null, 2000))
				return true;
		}
		for (Inventory inv : invs) {
			Coord c = inv.isRoom(w, h);
			if (c == null)
				continue;
			inv.wdgmsg("drop", c);
			if (waitFor(() -> gui.vhand == null, 2000))
				return true;
		}
		gui.error("No room in your inventory for the item in your hand.");
		return false;
	}

	private static boolean handHolds(Equipory eq, String res) {
		for (int s : new int[]{HAND_LEFT, HAND_RIGHT}) {
			WItem w = eq.slots[s];
			try {
				if (w != null && w.item.getres().name.equals(res))
					return true;
			} catch (Loading ignored) {
			}
		}
		return false;
	}

	private WItem findCarried(String res, Coord at, Inventory inv) {
		WItem any = null;
		for (WItem w : carriedItems()) {
			try {
				if (!w.item.getres().name.equals(res))
					continue;
			} catch (Loading l) {
				continue;
			}
			if (w.parent == inv && w.c.sub(1, 1).div(Inventory.sqsz).equals(at))
				return w;
			if (any == null || quality(w) > quality(any))
				any = w;
		}
		return any;
	}

	/** The main inventory plus the character's own container windows (belt, backpack, pouches). */
	private List<Inventory> ownInventories() {
		List<Inventory> res = new ArrayList<>();
		if (gui.maininv != null)
			res.add(gui.maininv);
		for (Widget w = gui.lchild; w != null; w = w.prev) {
			if (!(w instanceof GItem.ContentsWindow) || !((GItem.ContentsWindow) w).myOwnEquipory)
				continue;
			for (Widget ww : w.children()) {
				Inventory inv = Inventory.fromWidget(ww);
				if (inv != null && !res.contains(inv))
					res.add(inv);
			}
		}
		return res;
	}

	private List<WItem> carriedItems() {
		List<WItem> res = new ArrayList<>();
		for (Inventory inv : ownInventories()) {
			for (Widget w = inv.child; w != null; w = w.next) {
				if (w instanceof WItem)
					res.add((WItem) w);
			}
		}
		return res;
	}

	private static WItem best(List<WItem> items, java.util.function.Predicate<WItem> kind) {
		WItem best = null;
		for (WItem w : items) {
			if (w == null || !kind.test(w))
				continue;
			if (best == null || quality(w) > quality(best))
				best = w;
		}
		return best;
	}

	private static double quality(WItem w) {
		try {
			return w.item.getQBuff() != null ? w.item.getQBuff().q : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	private static String basename(WItem w) {
		try {
			Resource r = w.item.getres();
			return r != null ? r.basename() : null;
		} catch (Loading l) {
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	private FlowerMenu openFlowerMenu() {
		for (Widget w = gui.ui.root.child; w != null; w = w.next) {
			if (w instanceof FlowerMenu)
				return (FlowerMenu) w;
		}
		return null;
	}

	private Gob nearest(List<Gob> gobs) {
		Gob player = gui.map.player();
		if (player == null || gobs.isEmpty())
			return null;
		Gob best = null;
		double bd = Double.MAX_VALUE;
		for (Gob g : gobs) {
			double d = g.rc.dist(player.rc);
			if (d < bd) {
				bd = d;
				best = g;
			}
		}
		return best;
	}

	private Gob gob(long id) {
		return gui.map.glob.oc.getgob(id);
	}

	private void clickGob(Gob gob, int button, int modflags) {
		gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), button, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
	}

	private static long stallMs(long min) {
		Integer ping = GameUI.getPingValue();
		return Math.max(min, (ping != null ? ping : 100) * 6L);
	}

	private static boolean waitFor(BooleanSupplier cond, int timeoutMs) throws InterruptedException {
		long start = System.currentTimeMillis();
		while (!cond.getAsBoolean()) {
			if (System.currentTimeMillis() - start > timeoutMs)
				return false;
			Thread.sleep(25);
		}
		return true;
	}

	private boolean inArea(Coord2d c) {
		return c.x >= ul.x && c.x < br.x && c.y >= ul.y && c.y < br.y;
	}

	private static String resName(Gob g) {
		try {
			Resource res = g.getres();
			return res != null ? res.name : null;
		} catch (Loading l) {
			return null;
		}
	}
}
