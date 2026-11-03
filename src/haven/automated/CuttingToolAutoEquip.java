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

	/** True when choosing this flower-menu option should first equip the best cutting tool. */
	public static boolean wantsTool(String option) {
		if (!enabled() || option == null)
			return false;
		if (option.equals("Butcher"))
			return true;
		return option.equals("Split") && isBlock(lastRightClickedItemName);
	}

	private static class Tool {
		final WItem witem;
		final double q;
		final Inventory inv; // null when it's already in a hand
		Tool(WItem witem, double q, Inventory inv) {
			this.witem = witem;
			this.q = q;
			this.inv = inv;
		}
	}

	private static double quality(GItem item) {
		try {
			Quality q = ItemInfo.find(Quality.class, item.info());
			return q != null ? q.q : 0;
		} catch (Loading e) {
			return -1;
		}
	}

	private static boolean isSharpTool(GItem item) {
		try {
			return SHARP_TOOLS.contains(item.getname());
		} catch (Exception e) {
			return false;
		}
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

	private static Tool findBest(GameUI gui) {
		List<Tool> tools = new ArrayList<>();
		Equipory eq = gui.getequipory();
		if (eq != null) {
			for (int slot : new int[]{LEFT_HAND, RIGHT_HAND}) {
				WItem w = eq.slots[slot];
				if (w != null && isSharpTool(w.item))
					tools.add(new Tool(w, quality(w.item), null));
			}
		}
		for (Inventory inv : new Inventory[]{gui.maininv, belt(gui)}) {
			if (inv == null) continue;
			for (WItem w : inv.getAllItems()) {
				if (w.item != null && isSharpTool(w.item))
					tools.add(new Tool(w, quality(w.item), inv));
			}
		}
		Tool best = null;
		for (Tool t : tools) {
			// On a tie keep the one in hand, so we don't swap for nothing.
			if (best == null || t.q > best.q || (t.q == best.q && t.inv == null && best.inv != null))
				best = t;
		}
		return best;
	}

	/** Quick check used before deciding to delay an action: is there a better tool to put in hand? */
	public static boolean needsSwap(GameUI gui) {
		if (!enabled() || gui == null)
			return false;
		try {
			Tool best = findBest(gui);
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
	public static void equipBest(GameUI gui) {
		try {
			if (gui.vhand != null) {
				gui.ui.error("Auto-equip cutting tool: your cursor is holding an item, not switching tools.");
				return;
			}
			Tool best = findBest(gui);
			if (best == null) {
				gui.ui.error("Auto-equip cutting tool: no cutting tool (axe, knife, cleaver, sword) in your inventory or belt.");
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
			if (left == null && !twoHander) slot = LEFT_HAND;
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

			tool.wdgmsg("take", Coord.z);
			if (!waitFor(() -> gui.vhand != null && gui.vhand.item == tool)) {
				gui.ui.error("Auto-equip cutting tool: couldn't pick up the tool.");
				return;
			}
			eq.wdgmsg("drop", slot);
			if (!waitFor(() -> eq.slots[slot] != null && eq.slots[slot].item == tool)) {
				gui.ui.error("Auto-equip cutting tool: couldn't put the tool in your hand.");
				return;
			}
			// Whatever was in that hand is now on the cursor: put it where the tool was.
			if (handWasFull && waitFor(() -> gui.vhand != null) && gui.vhand != null) {
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
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			gui.ui.error("Auto-equip cutting tool: something went wrong, see the log.");
			e.printStackTrace();
		}
	}
}
