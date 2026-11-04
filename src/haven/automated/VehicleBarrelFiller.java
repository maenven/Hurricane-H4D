package haven.automated;

import haven.*;
import haven.automated.helpers.ClientMenu;

import java.awt.Color;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Ctrl+Right-Click on a well, cistern or mill → "Fill barrels from a vehicle", then click the cart,
 * wagon, snekkja or knarr that holds the barrels (or Ctrl+Right-Click the cart/wagon → "Fill barrels
 * at a well/cistern/mill", then click the source). The character takes every
 * object out of the vehicle one at a time; each empty barrel is filled at the source, and
 * everything goes back into the vehicle.
 *
 * Ported from Nurgling2's {@code nurgling/contextmenu/FillBarrelsFromVehicleAction} and
 * {@code nurgling/actions/TakeFromVehicleSlot}, rebuilt on the same pieces as
 * {@link VehicleAreaTransfer}. See doc/H4D-features/vehicle-barrel-filler.md.
 */
public class VehicleBarrelFiller implements Runnable {
	public static final String BARREL = "gfx/terobjs/barrel";

	/** A vehicle whose cargo is reached through a window: flower option, window title, cargo widget size. */
	private static final class WindowVehicle {
		final String res, name, flower, window;
		final int minSlots;

		WindowVehicle(String res, String name, String flower, String window, int minSlots) {
			this.res = res;
			this.name = name;
			this.flower = flower;
			this.window = window;
			this.minSlots = minSlots;
		}
	}

	private static final WindowVehicle[] WINDOW_VEHICLES = {
			new WindowVehicle(VehicleAreaTransfer.WAGON, "wagon", "Open", "Wagon", 20),
			new WindowVehicle("gfx/terobjs/vehicle/snekkja", "snekkja", "Cargo", "Snekkja", 16),
			new WindowVehicle("gfx/terobjs/vehicle/knarr", "knarr", "Cargo", "Knarr", 64),
	};

	private static final int CART_SLOTS = 6;
	/** How long walking to the source and filling one barrel may take. */
	private static final int FILL_TIMEOUT = 60000;

	private static volatile Thread running = null;

	private final GameUI gui;
	private final long sourceId, vehicleId;
	private final String sourceName;

	private int filled = 0, alreadyFull = 0, failed = 0;
	private String lastFailure = null;

	private VehicleBarrelFiller(GameUI gui, long sourceId, String sourceName, long vehicleId) {
		this.gui = gui;
		this.sourceId = sourceId;
		this.sourceName = sourceName;
		this.vehicleId = vehicleId;
	}

	// ---------------------------------------------------------------------------------------
	// UI thread
	// ---------------------------------------------------------------------------------------

	/**
	 * Called from MapView for Ctrl+Right-Click on a gob. On a well, cistern or mill it opens a
	 * client-side menu ("Fill barrels from a vehicle", or "Stop" while a run is going) and returns
	 * true, in which case the click must not be sent to the server. Shares the
	 * cart/wagon load/unload option.
	 */
	public static boolean ctrlRightClick(GameUI gui, Gob gob) {
		if (gui == null || gob == null || gui.map == null)
			return false;
		if (OptWnd.vehicleAreaTransferCheckBox == null || !OptWnd.vehicleAreaTransferCheckBox.a)
			return false;
		if (gui.vhand != null)
			return false;
		String name = resName(gob);
		if (!isSource(name))
			return false;
		if (isRunning()) {
			gui.add(new ClientMenu(new String[]{"Stop filling barrels"}, idx -> stop(gui, true)), new Coord(-1, -1));
			return true;
		}
		// Carrying something: leave the click alone.
		if (carriedBy(gui) != null)
			return false;
		final long sourceId = gob.id;
		final String sourceName = prettyName(name);
		gui.add(new ClientMenu(new String[]{"Fill barrels from a vehicle"}, idx -> {
			gui.map.startClientGobPick(veh -> vehiclePicked(gui, sourceId, sourceName, veh));
			gui.msg("Click the cart, wagon, snekkja or knarr holding the barrels. Right-click cancels.", Color.WHITE);
		}), new Coord(-1, -1));
		return true;
	}

	/** From the cart/wagon Ctrl+Right-Click menu: pick the source for this vehicle's barrels. */
	public static void pickSourceFor(GameUI gui, long vehicleId) {
		if (isRunning()) {
			gui.error("Already filling barrels. Ctrl+Right-Click the well, cistern or mill to stop.");
			return;
		}
		gui.map.startClientGobPick(src -> {
			String name = resName(src);
			if (!isSource(name)) {
				gui.error("That isn't a well, cistern or mill.");
				return;
			}
			Gob veh = gui.map.glob.oc.getgob(vehicleId);
			if (veh == null) {
				gui.error("The vehicle is out of sight.");
				return;
			}
			vehiclePicked(gui, src.id, prettyName(name), veh);
		});
		gui.msg("Click the well, cistern or mill to fill the barrels at. Right-click cancels.", Color.WHITE);
	}

	/** Wells, cisterns and mills: the objects that get the "Fill barrels" Ctrl+Right-Click menu. */
	public static boolean isSource(String res) {
		if (res == null || !res.startsWith("gfx/terobjs/") || res.startsWith("gfx/terobjs/arch/"))
			return false;
		String base = res.substring(res.lastIndexOf('/') + 1);
		return base.equals("well") || base.contains("cistern") || base.contains("mill") || base.contains("quern");
	}

	private static void vehiclePicked(GameUI gui, long sourceId, String sourceName, Gob veh) {
		if (!isVehicle(resName(veh))) {
			gui.error("That isn't a cart, wagon, snekkja or knarr. Ctrl+Right-Click the " + sourceName + " to start over.");
			return;
		}
		stop(gui, false);
		Thread t = new Thread(new VehicleBarrelFiller(gui, sourceId, sourceName, veh.id), "VehicleBarrelFiller");
		running = t;
		gui.msg("Filling the " + vehicleName(resName(veh)) + "'s barrels at the " + sourceName + ". Ctrl+Right-Click the " + sourceName + " to stop.", Color.WHITE);
		t.start();
	}

	private static boolean isRunning() {
		Thread t = running;
		return t != null && t.isAlive();
	}

	private static void stop(GameUI gui, boolean announce) {
		Thread t = running;
		if (t != null) {
			t.interrupt();
			running = null;
			if (announce && t.isAlive())
				gui.msg("Stopped filling barrels.", Color.WHITE);
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
			if (carried() != null) {
				gui.error("Put down what you're carrying first.");
				return;
			}
			Gob vehicle = gob(vehicleId);
			if (vehicle == null)
				return;
			if (VehicleAreaTransfer.CART.equals(resName(vehicle)))
				fromCart();
			else
				fromWindowVehicle(windowVehicle(resName(vehicle)));
			summary(false);
		} catch (InterruptedException e) {
			summary(true);
		} catch (Exception e) {
			gui.error("Barrel filling stopped: " + e.getMessage());
		} finally {
			if (running == Thread.currentThread())
				running = null;
		}
	}

	private void summary(boolean stopped) {
		StringBuilder sb = new StringBuilder(stopped ? "Stopped. " : "Done: ");
		sb.append("filled ").append(filled).append(" barrel").append(filled == 1 ? "" : "s");
		if (alreadyFull > 0)
			sb.append(", ").append(alreadyFull).append(" already had something in them");
		if (failed > 0)
			sb.append(", ").append(failed).append(" couldn't be filled").append(lastFailure != null ? " (" + lastFailure + ")" : "");
		sb.append(".");
		if (!stopped && filled == 0 && alreadyFull == 0 && failed == 0)
			sb = new StringBuilder("No barrels found in the vehicle.");
		gui.msg(sb.toString(), Color.WHITE);
	}

	private void fromCart() throws InterruptedException {
		for (int slot = 0; slot < CART_SLOTS; slot++) {
			Gob cart = vehicleOrFail();
			if ((cartBits(cart) & (4 << slot)) == 0)
				continue;
			clickGob(cart, 3, 0, slot + 2);
			if (!waitFor(() -> carried() != null, 30000)) {
				gui.error("Couldn't take slot " + (slot + 1) + " out of the cart; skipping it.");
				continue;
			}
			handleCarried();
		}
	}

	private void fromWindowVehicle(WindowVehicle wv) throws InterruptedException {
		if (wv == null)
			return;
		int count = -1;
		for (int idx = 0; count < 0 || idx < count; idx++) {
			Widget cargo = openCargo(wv);
			if (cargo == null) {
				gui.error("Couldn't open the " + wv.name + "'s cargo.");
				return;
			}
			if (count < 0)
				count = countObjects(cargo);
			if (idx >= count)
				break;
			Widget slot = objectAt(cargo, idx);
			if (slot == null)
				break;
			slot.wdgmsg("click", UI.scale(15, 15), 1, 0);
			if (!waitFor(() -> carried() != null, 30000)) {
				gui.error("Couldn't take object " + (idx + 1) + " out of the " + wv.name + "; skipping it.");
				continue;
			}
			handleCarried();
		}
	}

	/** Fills the carried object if it is an empty barrel, then puts it back in the vehicle. */
	private void handleCarried() throws InterruptedException {
		Gob obj = carried();
		if (obj == null)
			return;
		if (BARREL.equals(resName(obj))) {
			if (hasContents(obj))
				alreadyFull++;
			else
				fill(obj);
		}
		putBack(obj.id);
	}

	private void fill(Gob barrel) throws InterruptedException {
		Gob src = gob(sourceId);
		if (src == null) {
			throw new RuntimeException("the " + sourceName + " is out of sight");
		}
		final long bid = barrel.id;
		final int errSeq = gui.ui.serverErrorSeq;
		// Shift+Right-Click on the source with the barrel carried; the server walks the character there.
		clickGob(src, 3, UI.MOD_SHIFT, -1);
		boolean ok = waitFor(() -> hasContents(bid) || gui.ui.serverErrorSeq != errSeq, FILL_TIMEOUT);
		if (!ok || !hasContents(bid)) {
			// The overlay can lag behind the end of the walk a little.
			long until = System.currentTimeMillis() + 1500;
			while (!hasContents(bid) && gui.ui.serverErrorSeq == errSeq && System.currentTimeMillis() < until)
				Thread.sleep(50);
		}
		if (hasContents(bid)) {
			filled++;
		} else {
			failed++;
			if (gui.ui.serverErrorSeq != errSeq)
				lastFailure = gui.ui.lastServerError;
		}
	}

	private void putBack(long objId) throws InterruptedException {
		Gob vehicle = vehicleOrFail();
		clickGob(vehicle, 3, 0, -1);
		if (!waitFor(() -> !isCarried(objId), 30000))
			throw new RuntimeException("couldn't put the object back into the vehicle");
	}

	/** Opens (or reopens) the vehicle's cargo window and returns the cargo widget. */
	private Widget openCargo(WindowVehicle wv) throws InterruptedException {
		Window old = gui.getwnd(wv.window);
		if (old != null) {
			old.wdgmsg("close");
			waitFor(() -> gui.getwnd(wv.window) == null, 3000);
		}
		FlowerMenu.setNextSelection(wv.flower);
		clickGob(vehicleOrFail(), 3, 0, -1);
		if (!waitFor(() -> gui.getwnd(wv.window) != null, 30000))
			return null;
		Thread.sleep(300); // let the cargo widgets arrive
		Window wnd = gui.getwnd(wv.window);
		if (wnd == null)
			return null;
		for (Widget w : wnd.children()) {
			if (w instanceof Inventory)
				continue;
			if (w.children().size() >= wv.minSlots)
				return w;
		}
		return null;
	}

	private static int countObjects(Widget cargo) {
		int n = 0;
		for (Widget child : cargo.children())
			if (!isEmptySlot(child))
				n++;
		return n;
	}

	private static Widget objectAt(Widget cargo, int idx) {
		int i = 0;
		for (Widget child : cargo.children()) {
			if (isEmptySlot(child))
				continue;
			if (i == idx)
				return child;
			i++;
		}
		return null;
	}

	private static boolean isEmptySlot(Widget w) {
		// Slot backgrounds are a resource class (haven.res.ui.invsq.InvSquare) the client can't name directly.
		return w.getClass().getSimpleName().equals("InvSquare") || w instanceof GItem || w instanceof WItem;
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	private Gob vehicleOrFail() {
		Gob v = gob(vehicleId);
		if (v == null)
			throw new RuntimeException("the vehicle is out of sight");
		return v;
	}

	private Gob gob(long id) {
		return gui.map.glob.oc.getgob(id);
	}

	private void clickGob(Gob gob, int button, int modflags, int meshid) {
		gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), button, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, meshid);
	}

	private Gob carried() {
		return carriedBy(gui);
	}

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

	private boolean isCarried(long id) {
		Gob g = gob(id);
		if (g == null)
			return false;
		Following f = g.getattr(Following.class);
		return f != null && f.tgt == gui.map.plgob;
	}

	private boolean hasContents(long id) {
		Gob g = gob(id);
		return g != null && hasContents(g);
	}

	/** A barrel shows its contents as a {@code gfx/terobjs/barrel-<contents>} overlay; none = empty. */
	private static boolean hasContents(Gob barrel) {
		synchronized (barrel.ols) {
			for (Gob.Overlay ol : barrel.ols) {
				String n = ol.getSprResName();
				if (n != null && n.startsWith("gfx/terobjs/barrel-"))
					return true;
			}
		}
		return false;
	}

	private static int cartBits(Gob cart) {
		ResDrawable rd = cart.getattr(ResDrawable.class);
		if (rd == null || rd.sdt.rbuf.length == 0)
			return 0;
		return rd.sdt.checkrbuf(0) & 0xff;
	}

	private static boolean isVehicle(String res) {
		return VehicleAreaTransfer.CART.equals(res) || windowVehicle(res) != null;
	}

	private static WindowVehicle windowVehicle(String res) {
		if (res == null)
			return null;
		for (WindowVehicle wv : WINDOW_VEHICLES)
			if (wv.res.equals(res))
				return wv;
		return null;
	}

	private static String vehicleName(String res) {
		if (VehicleAreaTransfer.CART.equals(res))
			return "cart";
		WindowVehicle wv = windowVehicle(res);
		return wv != null ? wv.name : "vehicle";
	}

	/** Waits for {@code cond}; gives up after the timeout or once the character has stood still a while. */
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
			if (moving)
				stillSince = -1;
			else if (stillSince < 0)
				stillSince = now;
			else if (now - stillSince > stall)
				return cond.getAsBoolean();
			Thread.sleep(50);
		}
	}

	private static String resName(Gob g) {
		if (g == null)
			return null;
		try {
			Resource res = g.getres();
			return res != null ? res.name : null;
		} catch (Loading l) {
			return null;
		}
	}

	private static String prettyName(String resName) {
		String base = resName.substring(resName.lastIndexOf('/') + 1);
		return base.isEmpty() ? resName : base;
	}
}
