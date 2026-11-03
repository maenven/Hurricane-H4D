package haven.automated;

import haven.*;
import haven.res.ui.tt.q.quality.Quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * H4D: before butchering an animal or splitting a block into sticks, put the highest quality cutting tool
 * the player carries (main inventory, belt or hands) into a hand, so the action never runs with a worse
 * tool or none at all. Hooked from FlowerMenu.choose and the Ctrl+Right-Click quick-select in WItem.
 */
public class CuttingToolAutoEquip {
	/** The game's "Sharp Tool" item group. */
	private static final Set<String> SHARP_TOOLS = new HashSet<>(Arrays.asList(
			"Bronze Sword",
			"Butcher's Cleaver",
			"Ceramic Knife",
			"Flint Knife",
			"Fyrdsman's Sword",
			"Hirdsman's Sword",
			"Metal Axe",
			"Obsidian Dagger",
			"Stone Axe",
			"Tinker's Throwing Axe",
			"Woodsman's Axe"
	));

	/** Hand items that can't be put into an inventory square (same list EquipFromBelt uses). */
	private static final String[] RESTRICTED_HAND_ITEMS = {"bucket", "pickingbasket", "splint"};

	private static final int LEFT_HAND = 6, RIGHT_HAND = 7;
	private static final long STEP_TIMEOUT_MS = 2000;

	/** Name of the last inventory item the player right-clicked, so "Split" only reacts to blocks. */
	public static volatile String lastRightClickedItemName = null;

	public static boolean enabled() {
		return OptWnd.autoEquipCuttingToolCheckBox != null && OptWnd.autoEquipCuttingToolCheckBox.a;
	}

	public static boolean isBlock(String itemName) {
		return itemName != null && itemName.startsWith("Block of");
	}

	/** Which tools an action wants and how they are ranked. */
	public enum Policy {
		/** Any sharp tool, highest quality wins. */
		SHARP,
		/** Axes only, by type first (Stone < Metal < Woodsman's), then quality. */
		AXE
	}

	/** Axe types for chopping, worst to best. */
	private static final List<String> AXE_RANK = Arrays.asList("Stone Axe", "Metal Axe", "Woodsman's Axe");

	/** The tool policy for this flower-menu option, or null when it should be left alone. */
	public static Policy policyFor(String option) {
		if (!enabled() || option == null)
			return null;
		if (option.equals("Butcher"))
			return Policy.SHARP;
		if (option.equals("Split") && isBlock(lastRightClickedItemName))
			return Policy.SHARP;
		if (option.equals("Chop") || option.equals("Chop into blocks"))
			return Policy.AXE;
		return null;
	}

	private static class Tool {
		final WItem witem;
		final double q;
		final int rank;
		final Inventory inv; // null when it's already in a hand
		Tool(WItem witem, double q, int rank, Inventory inv) {
			this.witem = witem;
			this.q = q;
			this.rank = rank;
			this.inv = inv;
		}
		/** Positive when this tool is better than the other. */
		int compare(Tool o) {
			if (rank != o.rank) return Integer.compare(rank, o.rank);
			return Double.compare(q, o.q);
		}
	}

	private static String name(GItem item) {
		try {
			return item.getname();
		} catch (Exception e) {
			return null;
		}
	}

	/** Rank of this item under the policy, or -1 when it doesn't qualify. */
	private static int rank(GItem item, Policy policy) {
		String n = name(item);
		if (n == null)
			return -1;
		if (policy == Policy.AXE)
			return AXE_RANK.indexOf(n);
		return SHARP_TOOLS.contains(n) ? 0 : -1;
	}

	private static double quality(GItem item) {
		try {
			Quality q = ItemInfo.find(Quality.class, item.info());
			return q != null ? q.q : 0;
		} catch (Loading e) {
			return -1;
		}
	}

	/**
	 * The server sometimes puts the new tool into the free hand instead of the one we dropped it on, which
	 * leaves both the old and the new tool in hand. Put the worse one back into the inventory.
	 */
	private static void putAwayWorseTool(GameUI gui, Equipory eq, Policy policy, Inventory from) throws InterruptedException {
		Thread.sleep(150); // let the hand slots settle
		WItem l = eq.slots[LEFT_HAND], r = eq.slots[RIGHT_HAND];
		if (l == null || r == null || l.item == r.item || gui.vhand != null)
			return;
		int rl = rank(l.item, policy), rr = rank(r.item, policy);
		if (rl < 0 || rr < 0)
			return;
		Tool tl = new Tool(l, quality(l.item), rl, null), tr = new Tool(r, quality(r.item), rr, null);
		WItem worse = tl.compare(tr) >= 0 ? r : l;
		Coord isz = worse.sz.div(Inventory.sqsz);
		int w = Math.max(1, isz.x), h = Math.max(1, isz.y);
		worse.item.wdgmsg("take", Coord.z);
		if (!waitFor(() -> gui.vhand != null))
			return;
		Inventory target = from;
		Coord spot = from.isRoom(w, h);
		if (spot == null) {
			target = gui.maininv;
			spot = gui.maininv.isRoom(w, h);
		}
		if (spot == null) {
			gui.ui.error("Auto-equip cutting tool: no room to put the old tool away, it's on your cursor.");
			return;
		}
		target.wdgmsg("drop", spot);
		waitFor(() -> gui.vhand == null);
	}

	private static boolean newIn(WItem now, WItem before, Policy policy) {
		return now != null && now != before && rank(now.item, policy) >= 0;
	}

	private static Inventory belt(GameUI gui) {
		for (Widget w = gui.lchild; w != null; w = w.prev) {
			if (!(w instanceof GItem.ContentsWindow) || !((GItem.ContentsWindow) w).myOwnEquipory) continue;
			if (!((GItem.ContentsWindow) w).cap.contains("Belt")) continue;
			for (Widget ww : w.children()) {
				Inventory inv = Inventory.fromWidget(ww);
				if (inv != null)
					return inv;
			}
		}
		return null;
	}

	private static Tool findBest(GameUI gui, Policy policy) {
		List<Tool> tools = new ArrayList<>();
		Equipory eq = gui.getequipory();
		if (eq != null) {
			for (int slot : new int[]{LEFT_HAND, RIGHT_HAND}) {
				WItem w = eq.slots[slot];
				int r = w != null ? rank(w.item, policy) : -1;
				if (r >= 0)
					tools.add(new Tool(w, quality(w.item), r, null));
			}
		}
		for (Inventory inv : new Inventory[]{gui.maininv, belt(gui)}) {
			if (inv == null) continue;
			for (WItem w : inv.getAllItems()) {
				int r = w.item != null ? rank(w.item, policy) : -1;
				if (r >= 0)
					tools.add(new Tool(w, quality(w.item), r, inv));
			}
		}
		Tool best = null;
		for (Tool t : tools) {
			// On a tie keep the one in hand, so we don't swap for nothing.
			int c = best == null ? 1 : t.compare(best);
			if (c > 0 || (c == 0 && t.inv == null && best.inv != null))
				best = t;
		}
		return best;
	}

	/** Quick check used before deciding to delay an action: is there a better tool to put in hand? */
	public static boolean needsSwap(GameUI gui, Policy policy) {
		if (!enabled() || gui == null || policy == null)
			return false;
		try {
			Tool best = findBest(gui, policy);
			return best != null && best.inv != null;
		} catch (Exception e) {
			return false;
		}
	}

	private static boolean isRestricted(WItem w) {
		try {
			String name = w.item.getres().name;
			for (String r : RESTRICTED_HAND_ITEMS)
				if (name.contains(r))
					return true;
		} catch (Loading ignored) {
		}
		return false;
	}

	private interface Cond {
		boolean ok();
	}

	private static boolean waitFor(Cond c) throws InterruptedException {
		long end = System.currentTimeMillis() + STEP_TIMEOUT_MS;
		while (System.currentTimeMillis() < end) {
			if (c.ok())
				return true;
			Thread.sleep(25);
		}
		return c.ok();
	}

	/**
	 * Puts the best cutting tool into a hand. Blocks until done (call it off the UI thread).
	 * Never throws; on any problem it tells the player and lets the action go ahead.
	 */
	public static void equipBest(GameUI gui, Policy policy) {
		try {
			if (gui.vhand != null) {
				gui.ui.error("Auto-equip cutting tool: your cursor is holding an item, not switching tools.");
				return;
			}
			Tool best = findBest(gui, policy);
			if (best == null) {
				gui.ui.error(policy == Policy.AXE
						? "Auto-equip cutting tool: no Stone, Metal or Woodsman's Axe in your inventory or belt."
						: "Auto-equip cutting tool: no cutting tool (axe, knife, cleaver, sword) in your inventory or belt.");
				return;
			}
			if (best.inv == null)
				return; // already in hand

			Equipory eq = gui.getequipory();
			if (eq == null)
				return;
			WItem left = eq.slots[LEFT_HAND], right = eq.slots[RIGHT_HAND];
			boolean twoHander = left != null && right != null && left.item == right.item;
			int slot;
			// Replace a worse tool of the same kind already in hand first, so you don't end up holding
			// two axes; then use an empty hand; then swap out whatever else is held.
			if (!twoHander && left != null && rank(left.item, policy) >= 0) slot = LEFT_HAND;
			else if (!twoHander && right != null && rank(right.item, policy) >= 0) slot = RIGHT_HAND;
			else if (left == null && !twoHander) slot = LEFT_HAND;
			else if (right == null && !twoHander) slot = RIGHT_HAND;
			else if (!isRestricted(left)) slot = LEFT_HAND;
			else if (!isRestricted(right)) slot = RIGHT_HAND;
			else {
				gui.ui.error("Auto-equip cutting tool: both hands hold items that can't go into an inventory.");
				return;
			}

			Inventory from = best.inv;
			Coord origin = best.witem.c.sub(1, 1).div(Inventory.sqsz);
			GItem tool = best.witem.item;
			boolean handWasFull = eq.slots[slot] != null;

			// The server re-creates item widgets when they move (inventory -> cursor -> hand), so we can't
			// follow the tool by widget identity; we watch the cursor and the hand slot instead, the same
			// take / drop-in-hand / drop-back sequence the "Equip from Belt" buttons use.
			WItem beforeL = eq.slots[LEFT_HAND], beforeR = eq.slots[RIGHT_HAND];
			tool.wdgmsg("take", Coord.z);
			if (!waitFor(() -> gui.vhand != null)) {
				gui.ui.error("Auto-equip cutting tool: couldn't pick up the tool.");
				return;
			}
			eq.wdgmsg("drop", slot);
			// The server may put the tool in the other hand than the one we dropped it on, so accept either.
			if (!waitFor(() -> (newIn(eq.slots[LEFT_HAND], beforeL, policy) || newIn(eq.slots[RIGHT_HAND], beforeR, policy))
					&& (handWasFull ? (gui.vhand != null) : (gui.vhand == null)))) {
				gui.ui.error("Auto-equip cutting tool: couldn't put the tool in your hand.");
				return;
			}
			// Whatever was in that hand is now on the cursor: put it where the tool was.
			if (handWasFull && gui.vhand != null) {
				from.wdgmsg("drop", origin);
				if (!waitFor(() -> gui.vhand == null) && gui.vhand != null) {
					// Didn't fit there (e.g. a bigger item): try any free spot in the main inventory.
					WItem held = gui.vhand;
					Coord sz = held.sz.div(Inventory.sqsz);
					Coord free = gui.maininv.isRoom(Math.max(1, sz.x), Math.max(1, sz.y));
					if (free != null) {
						gui.maininv.wdgmsg("drop", free);
						waitFor(() -> gui.vhand == null);
					}
					if (gui.vhand != null)
						gui.ui.error("Auto-equip cutting tool: no room for the item from your hand, it's still on your cursor.");
				}
			}
			putAwayWorseTool(gui, eq, policy, from);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			gui.ui.error("Auto-equip cutting tool: something went wrong, see the log.");
			e.printStackTrace();
		}
	}
}
