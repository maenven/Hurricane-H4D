package haven.automated;

import haven.*;
import haven.automated.helpers.ClientMenu;
import haven.automated.helpers.HitBoxes;

import java.awt.Color;
import java.util.*;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Ctrl+Right-Click on a cart or a wagon: load it from objects lying in a dragged-out area, or
 * unload its cargo into free spots of a dragged-out area. Ported from the Nurgling2 client's
 * "Load vehicle" / "Unload vehicle" context actions, rewritten on Hurricane's own pieces
 * (MapView area selection, HitBoxes, plain wdgmsg clicks that let the server walk the character).
 *
 * Everything that touches widgets (the menus, the area selection) runs on the UI thread; the
 * loading/unloading itself runs on its own thread, kept in {@link GameUI#vehicleAreaTransferThread}.
 */
public class VehicleAreaTransfer implements Runnable {
	public static final String CART = "gfx/terobjs/vehicle/cart";
	public static final String WAGON = "gfx/terobjs/vehicle/wagon";

	private static final int CART_SLOTS = 6;
	private static final int WAGON_SLOTS = 20;
	/** Clear ground kept between an unloaded object and anything else, in world units (11 = one tile). */
	private static final double GAP = 1.0;
	/** Half size used when an object's hitbox is unknown. */
	private static final double DEFAULT_HALF = 5.5;

	private static volatile String runningLabel = null;

	public enum Mode {LOAD, UNLOAD}

	private final GameUI gui;
	private final long vehicleId;
	private final Mode mode;
	private final Coord2d ul, br; // world coordinates of the selected area, br exclusive
	private final String loadRes; // the kind of object to load (LOAD only)

	private VehicleAreaTransfer(GameUI gui, long vehicleId, Mode mode, Coord2d ul, Coord2d br, String loadRes) {
		this.gui = gui;
		this.vehicleId = vehicleId;
		this.mode = mode;
		this.ul = ul;
		this.br = br;
		this.loadRes = loadRes;
	}

	// ---------------------------------------------------------------------------------------
	// UI thread: the Ctrl+Right-Click hook, the menus and the area selection
	// ---------------------------------------------------------------------------------------

	/**
	 * Called from MapView for Ctrl+Right-Click on a gob. Returns true when it opened its menu,
	 * in which case the click must not be sent to the server.
	 */
	public static boolean ctrlRightClick(GameUI gui, Gob gob) {
		if (gui == null || gob == null || gui.map == null)
			return false;
		if (OptWnd.vehicleAreaTransferCheckBox == null || !OptWnd.vehicleAreaTransferCheckBox.a)
			return false;
		if (gui.vhand != null)
			return false;
		String name = resName(gob);
		if (!CART.equals(name) && !WAGON.equals(name))
			return false;

		if (isRunning(gui)) {
			String label = runningLabel;
			gui.add(new ClientMenu(new String[]{"Stop " + (label != null ? label : "loading/unloading")}, idx -> stop(gui, true)), new Coord(-1, -1));
			return true;
		}
		// Carrying something: leave the click alone, so it stores the object like it always did.
		if (carriedBy(gui) != null)
			return false;

		final long vehicleId = gob.id;
		final String vehicleName = CART.equals(name) ? "cart" : "wagon";
		gui.add(new ClientMenu(new String[]{"Load " + vehicleName + " from area", "Unload " + vehicleName + " into area"}, idx -> {
			Mode mode = (idx == 0) ? Mode.LOAD : Mode.UNLOAD;
			String prompt = (mode == Mode.LOAD)
					? "Drag a rectangle over the objects to load into the " + vehicleName + ". Right-click cancels."
					: "Drag a rectangle where the " + vehicleName + "'s cargo should go. Right-click cancels.";
			boolean started = gui.map.selectAreaClientSide((a, b) -> areaSelected(gui, vehicleId, vehicleName, mode, a, b));
			if (started)
				gui.msg(prompt, Color.WHITE);
			else
				gui.error("Another area selection is already active.");
		}), new Coord(-1, -1));
		return true;
	}

	private static void areaSelected(GameUI gui, long vehicleId, String vehicleName, Mode mode, Coord tul, Coord tbr) {
		Coord2d ul = new Coord2d(tul.x * MCache.tilesz.x, tul.y * MCache.tilesz.y);
		Coord2d br = new Coord2d(tbr.x * MCache.tilesz.x, tbr.y * MCache.tilesz.y);
		if (mode == Mode.UNLOAD) {
			start(gui, new VehicleAreaTransfer(gui, vehicleId, Mode.UNLOAD, ul, br, null), "unloading the " + vehicleName);
			return;
		}

		// Loading: find out which kinds of object are in the area and let the player pick one.
		Map<String, Integer> counts = new HashMap<>();
		for (Gob g : Utils.getAllGobs(gui)) {
			if (g.id == vehicleId || !inArea(g.rc, ul, br) || !loadCandidate(gui, g))
				continue;
			counts.merge(resName(g), 1, Integer::sum);
		}
		if (counts.isEmpty()) {
			gui.error("Nothing in that area that could be loaded.");
			return;
		}
		List<String> kinds = new ArrayList<>(counts.keySet());
		kinds.sort((a, b) -> {
			int c = Integer.compare(counts.get(b), counts.get(a));
			return (c != 0) ? c : prettyName(a).compareTo(prettyName(b));
		});
		if (kinds.size() == 1) {
			String kind = kinds.get(0);
			start(gui, new VehicleAreaTransfer(gui, vehicleId, Mode.LOAD, ul, br, kind), "loading " + prettyName(kind) + " into the " + vehicleName);
			return;
		}
		if (kinds.size() > 9)
			kinds = kinds.subList(0, 9);
		final List<String> shown = kinds;
		String[] opts = new String[shown.size()];
		for (int i = 0; i < shown.size(); i++)
			opts[i] = prettyName(shown.get(i)) + " (" + counts.get(shown.get(i)) + ")";
		gui.add(new ClientMenu(opts, idx -> {
			String kind = shown.get(idx);
			start(gui, new VehicleAreaTransfer(gui, vehicleId, Mode.LOAD, ul, br, kind), "loading " + prettyName(kind) + " into the " + vehicleName);
		}), new Coord(-1, -1));
	}

	private static void start(GameUI gui, VehicleAreaTransfer bot, String label) {
		stop(gui, false);
		runningLabel = label;
		Thread t = new Thread(bot, "VehicleAreaTransfer");
		gui.vehicleAreaTransferThread = t;
		gui.msg("Started " + label + ". Ctrl+Right-Click the vehicle again to stop.", Color.WHITE);
		t.start();
	}

	private static boolean isRunning(GameUI gui) {
		Thread t = gui.vehicleAreaTransferThread;
		return t != null && t.isAlive();
	}

	private static void stop(GameUI gui, boolean announce) {
		Thread t = gui.vehicleAreaTransferThread;
		if (t != null) {
			t.interrupt();
			gui.vehicleAreaTransferThread = null;
			if (announce && t.isAlive())
				gui.msg("Stopped " + (runningLabel != null ? runningLabel : "loading/unloading") + ".", Color.WHITE);
		}
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
			if (mode == Mode.LOAD)
				load();
			else
				unload();
		} catch (InterruptedException ignored) {
		} catch (Exception e) {
			gui.error("Cart/wagon " + (mode == Mode.LOAD ? "loading" : "unloading") + " stopped: " + e.getMessage());
		} finally {
			if (gui.vehicleAreaTransferThread == Thread.currentThread()) {
				gui.vehicleAreaTransferThread = null;
				runningLabel = null;
			}
		}
	}

	private void load() throws InterruptedException {
		Set<Long> skipped = new HashSet<>();
		int loaded = 0;
		while (!Thread.currentThread().isInterrupted()) {
			Gob vehicle = vehicle();
			if (vehicle == null) {
				gui.error("The vehicle is gone.");
				break;
			}
			if (isCart(vehicle) && cartFreeSlots(vehicle) == 0) {
				gui.msg("The cart is full. Loaded " + loaded + ".", Color.WHITE);
				break;
			}
			Gob target = nearestToLoad(vehicle, skipped);
			if (target == null) {
				gui.msg("Done: loaded " + loaded + (skipped.isEmpty() ? "." : ", skipped " + skipped.size() + " that couldn't be lifted."), Color.WHITE);
				break;
			}

			// Lift it. The server walks the character there.
			Coord2d origRc = target.rc;
			double origA = target.a;
			gui.wdgmsg("act", "carry");
			gui.map.wdgmsg("click", Coord.z, target.rc.floor(posres), 1, 0, 0, (int) target.id, target.rc.floor(posres), 0, -1);
			final long tid = target.id;
			if (!waitFor(() -> isCarried(tid), 30000)) {
				skipped.add(tid);
				continue;
			}

			// Put it in the vehicle.
			Gob lifted = target;
			clickGob(vehicle, 3, 0, -1);
			if (!waitFor(() -> !isCarried(tid), 30000)) {
				// Vehicle full or unreachable: set the object back down where it was.
				place(lifted, origRc, origA);
				gui.msg("The " + (isCart(vehicle) ? "cart" : "wagon") + " is full or can't be reached. Loaded " + loaded + ".", Color.WHITE);
				break;
			}
			loaded++;
		}
		resetCursor();
	}

	private void unload() throws InterruptedException {
		int unloaded = 0;
		while (!Thread.currentThread().isInterrupted()) {
			Gob vehicle = vehicle();
			if (vehicle == null) {
				gui.error("The vehicle is gone.");
				break;
			}
			Boolean took = isCart(vehicle) ? takeFromCart(vehicle) : takeFromWagon(vehicle);
			if (took == null) {
				gui.msg("Done: the " + (isCart(vehicle) ? "cart" : "wagon") + " is empty. Unloaded " + unloaded + ".", Color.WHITE);
				break;
			}
			Gob carried = carriedBy(gui);
			if (!took || carried == null) {
				gui.error("Couldn't take anything out of the vehicle. Unloaded " + unloaded + ".");
				break;
			}
			Coord2d spot = freeSpot(carried);
			if (spot == null) {
				// No room left: put it back in.
				clickGob(vehicle, 3, 0, -1);
				final long cid = carried.id;
				waitFor(() -> !isCarried(cid), 30000);
				gui.msg("No free space left in the area. Unloaded " + unloaded + ".", Color.WHITE);
				break;
			}
			if (!place(carried, spot, 0)) {
				gui.error("Couldn't set the object down. Unloaded " + unloaded + ".");
				break;
			}
			unloaded++;
		}
	}

	/** Takes one object out of a cart. Returns null when the cart is empty. */
	private Boolean takeFromCart(Gob cart) throws InterruptedException {
		int bits = cartBits(cart);
		for (int i = 0; i < CART_SLOTS; i++) {
			if ((bits & (4 << i)) != 0) {
				clickGob(cart, 3, 0, i + 2);
				return waitFor(() -> carriedBy(gui) != null, 30000);
			}
		}
		return null;
	}

	/** Takes one object out of a wagon through its cargo window. Returns null when the wagon is empty. */
	private Boolean takeFromWagon(Gob wagon) throws InterruptedException {
		Window wnd = gui.getwnd("Wagon");
		if (wnd == null) {
			FlowerMenu.setNextSelection("Open");
			clickGob(wagon, 3, 0, -1);
			if (!waitFor(() -> gui.getwnd("Wagon") != null, 30000))
				return false;
			wnd = gui.getwnd("Wagon");
			Thread.sleep(300); // let the cargo widgets arrive
		}
		Widget cargo = wagonCargo(wnd);
		if (cargo == null)
			return false;
		for (Widget child : cargo.children()) {
			if (!isEmptySlot(child)) {
				child.wdgmsg("click", UI.scale(15, 15), 1, 0);
				return waitFor(() -> carriedBy(gui) != null, 30000);
			}
		}
		return null;
	}

	/** The widget in the wagon window that holds the cargo slots. */
	private static Widget wagonCargo(Window wnd) {
		if (wnd == null)
			return null;
		for (Widget w : wnd.children()) {
			if (w instanceof Inventory)
				continue;
			if (w.children().size() >= WAGON_SLOTS)
				return w;
		}
		return null;
	}

	private static boolean isEmptySlot(Widget w) {
		// The slot backgrounds are a resource class (haven.res.ui.invsq.InvSquare) the client can't name directly.
		return w.getClass().getSimpleName().equals("InvSquare") || w instanceof GItem || w instanceof WItem;
	}

	/** Sets a carried object down at {@code pos}, facing {@code a}. */
	private boolean place(Gob carried, Coord2d pos, double a) throws InterruptedException {
		Gob player = gui.map.player();
		if (player == null)
			return false;
		final long cid = carried.id;
		// Shift+Right-Click on what we carry gives the placement ghost, like Nurgling's NUtils.place.
		gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, UI.MOD_SHIFT, 0, (int) cid, player.rc.floor(posres), 0, -1);
		if (!waitFor(gui.map::isPlacingReady, 5000))
			return false;
		gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(a * 32768 / Math.PI), 1, UI.MOD_SHIFT);
		return waitFor(() -> !isCarried(cid), 30000);
	}

	/**
	 * Free spot for the carried object inside the area, filling from the top-left corner.
	 * Objects go down unrotated; every other object's hitbox (rotated, as an axis-aligned box,
	 * mirrored in y to be safe) plus {@link #GAP} is kept clear.
	 */
	private Coord2d freeSpot(Gob carried) {
		double[] own = halfExtents(carried);
		double hx = own[0], hy = own[1];
		double x0 = ul.x + hx, x1 = br.x - hx, y0 = ul.y + hy, y1 = br.y - hy;
		if (x0 > x1 || y0 > y1)
			return null;

		List<double[]> blocks = new ArrayList<>();
		long plgob = gui.map.plgob;
		for (Gob g : Utils.getAllGobs(gui)) {
			if (g.id == carried.id || g.id == plgob || g.getattr(Following.class) != null)
				continue;
			String name = resName(g);
			if (name == null || name.startsWith("gfx/borka/"))
				continue;
			double[] box = worldBox(g);
			if (box == null)
				continue;
			box[0] -= GAP; box[1] -= GAP; box[2] += GAP; box[3] += GAP;
			if (box[2] <= ul.x || box[0] >= br.x || box[3] <= ul.y || box[1] >= br.y)
				continue;
			blocks.add(box);
		}

		for (double y = y0; y <= y1; y += 1) {
			for (double x = x0; x <= x1; x += 1) {
				double ax = x - hx, bx = x + hx, ay = y - hy, by = y + hy;
				boolean free = true;
				for (double[] b : blocks) {
					if (ax < b[2] && bx > b[0] && ay < b[3] && by > b[1]) {
						free = false;
						// Skip past this obstacle along x.
						x = Math.max(x, b[2] + hx - 1);
						break;
					}
				}
				if (free)
					return new Coord2d(x, y);
			}
		}
		return null;
	}

	/** World-space axis-aligned box {minx, miny, maxx, maxy} around a gob's hitbox, or null. */
	private static double[] worldBox(Gob g) {
		List<Coord2d> pts = hitboxPoints(g);
		if (pts == null)
			return null;
		double cos = Math.cos(g.a), sin = Math.sin(g.a);
		double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		for (Coord2d p : pts) {
			for (double py : new double[]{p.y, -p.y}) {
				double x = g.rc.x + p.x * cos - py * sin;
				double y = g.rc.y + p.x * sin + py * cos;
				box[0] = Math.min(box[0], x);
				box[1] = Math.min(box[1], y);
				box[2] = Math.max(box[2], x);
				box[3] = Math.max(box[3], y);
			}
		}
		return box;
	}

	/** Half width/height of a gob's hitbox when it is placed unrotated. */
	private static double[] halfExtents(Gob g) {
		List<Coord2d> pts = hitboxPoints(g);
		if (pts == null)
			return new double[]{DEFAULT_HALF, DEFAULT_HALF};
		double hx = 0, hy = 0;
		for (Coord2d p : pts) {
			hx = Math.max(hx, Math.abs(p.x));
			hy = Math.max(hy, Math.abs(p.y));
		}
		if (hx == 0 || hy == 0)
			return new double[]{DEFAULT_HALF, DEFAULT_HALF};
		return new double[]{hx, hy};
	}

	private static List<Coord2d> hitboxPoints(Gob g) {
		String name = resName(g);
		if (name == null)
			return null;
		HitBoxes.CollisionBoxSecondary[] boxes = HitBoxes.collisionBoxMap.get(name);
		if (boxes == null)
			return null;
		List<Coord2d> pts = new ArrayList<>();
		for (HitBoxes.CollisionBoxSecondary b : boxes) {
			if (b.hitAble && b.coords != null)
				pts.addAll(Arrays.asList(b.coords));
		}
		return pts.isEmpty() ? null : pts;
	}

	private Gob nearestToLoad(Gob vehicle, Set<Long> skipped) {
		Gob player = gui.map.player();
		Coord2d from = (player != null) ? player.rc : vehicle.rc;
		Gob best = null;
		double bestDist = Double.MAX_VALUE;
		for (Gob g : Utils.getAllGobs(gui)) {
			if (g.id == vehicle.id || skipped.contains(g.id) || !inArea(g.rc, ul, br))
				continue;
			if (!loadRes.equals(resName(g)) || !loadCandidate(gui, g))
				continue;
			double d = g.rc.dist(from);
			if (d < bestDist) {
				bestDist = d;
				best = g;
			}
		}
		return best;
	}

	/**
	 * Whether a gob is worth offering for loading. The client can't know for sure what is
	 * liftable, so this only filters out what obviously isn't (players, live animals, growing
	 * trees and bushes, plants, ground items, stockpiles, buildings); anything that turns out
	 * not to lift gets skipped.
	 */
	private static boolean loadCandidate(GameUI gui, Gob g) {
		if (g.id == gui.map.plgob || g.getattr(Following.class) != null)
			return false;
		String n = resName(g);
		if (n == null)
			return false;
		if (n.startsWith("gfx/borka/") || n.startsWith("gfx/terobjs/vehicle/") || n.startsWith("gfx/tiles/")
				|| n.startsWith("gfx/terobjs/herbs/") || n.startsWith("gfx/terobjs/items/") || n.startsWith("gfx/terobjs/plants/")
				|| n.startsWith("gfx/terobjs/bushes/") || n.startsWith("gfx/terobjs/arch/") || n.startsWith("gfx/terobjs/stockpile")
				|| n.startsWith("gfx/fx/") || n.startsWith("gfx/terobjs/clue"))
			return false;
		if (n.startsWith("gfx/terobjs/trees/") && !n.endsWith("log"))
			return false;
		if (n.startsWith("gfx/kritter/")) {
			try {
				return g.getPoses().contains("knock");
			} catch (Exception e) {
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	private Gob vehicle() {
		return gui.map.glob.oc.getgob(vehicleId);
	}

	private void clickGob(Gob gob, int button, int modflags, int meshid) {
		gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), button, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, meshid);
	}

	/** The object the player is carrying, or null. */
	private static Gob carriedBy(GameUI gui) {
		long plgob = gui.map.plgob;
		if (plgob < 0)
			return null;
		for (Gob g : Utils.getAllGobs(gui)) {
			Following f = g.getattr(Following.class);
			if (f != null && f.tgt == plgob)
				return g;
		}
		return null;
	}

	private boolean isCarried(long gobid) {
		Gob g = gui.map.glob.oc.getgob(gobid);
		if (g == null)
			return false;
		Following f = g.getattr(Following.class);
		return f != null && f.tgt == gui.map.plgob;
	}

	/** Bits 2..7 of a cart's draw data tell which of its six slots hold something. */
	private static int cartBits(Gob cart) {
		ResDrawable rd = cart.getattr(ResDrawable.class);
		if (rd == null)
			return 0;
		if (rd.sdt.rbuf.length == 0)
			return 0;
		return rd.sdt.checkrbuf(0) & 0xff;
	}

	private static int cartFreeSlots(Gob cart) {
		int bits = cartBits(cart), used = 0;
		for (int i = 0; i < CART_SLOTS; i++)
			if ((bits & (4 << i)) != 0)
				used++;
		return CART_SLOTS - used;
	}

	private static boolean isCart(Gob g) {
		return CART.equals(resName(g));
	}

	private void resetCursor() {
		// A failed lift leaves the "carry" cursor up; a right-click on the ground clears it.
		try {
			if (carriedBy(gui) == null && !gui.ui.checkCursorImage("gfx/hud/curs/arw")) {
				Gob player = gui.map.player();
				if (player != null)
					gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, 0);
			}
		} catch (Exception ignored) {
		}
	}

	/**
	 * Waits for {@code cond}. Gives up after {@code timeoutMs}, or earlier once the character has
	 * stood still for a while without it happening (the server refused, or the way is blocked).
	 */
	private boolean waitFor(BooleanSupplier cond, int timeoutMs) throws InterruptedException {
		Integer ping = GameUI.getPingValue();
		long stall = Math.max(2000, (ping != null ? ping : 100) * 4L);
		long start = System.currentTimeMillis();
		long stillSince = -1;
		while (true) {
			if (cond.getAsBoolean())
				return true;
			long now = System.currentTimeMillis();
			if (now - start > timeoutMs)
				return false;
			Gob player = gui.map.player();
			boolean moving = player != null && player.getv() > 0;
			if (moving) {
				stillSince = -1;
			} else if (stillSince < 0) {
				stillSince = now;
			} else if (now - stillSince > stall) {
				return cond.getAsBoolean();
			}
			Thread.sleep(50);
		}
	}

	private static boolean inArea(Coord2d c, Coord2d ul, Coord2d br) {
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

	private static String prettyName(String resName) {
		String base = resName.substring(resName.lastIndexOf('/') + 1);
		if (base.isEmpty())
			return resName;
		return Character.toUpperCase(base.charAt(0)) + base.substring(1);
	}
}
