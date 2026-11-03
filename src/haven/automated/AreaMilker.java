package haven.automated;

import haven.*;

import java.awt.Color;
import java.util.*;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Menu-grid button (Other Scripts & Tools): drag a rectangle over the cows, goats and sheep to milk,
 * then one over the empty barrels. The character lifts an empty barrel, milks every animal in the
 * first area into it, sets the barrel back where it stood once it is full, and takes the next empty
 * barrel from the barrel area until every animal is milked or no empty barrel is left.
 *
 * Mechanics from Nurgling2's {@code nurgling/actions/MilkAnimalsAction}: lift the barrel, then
 * Shift+Right-Click each animal while carrying it. Without the cattle roster the client can't tell a
 * lactating animal from a dry one (or a bull/ram), so every adult cow, nanny goat and sheep is tried
 * and the server's answer decides: an error message means "skip this animal", one that mentions
 * "full" means "swap the barrel". Each species gets its own barrel, so milks never mix.
 */
public class AreaMilker implements Runnable {
	public static final String BARREL = "gfx/terobjs/barrel";

	/** Milkable animals by resource, in the order they are milked. */
	private static final Map<String, String> ANIMALS = new LinkedHashMap<>();
	static {
		ANIMALS.put("gfx/kritter/cattle/cattle", "cow");
		ANIMALS.put("gfx/kritter/goat/nanny", "goat");
		ANIMALS.put("gfx/kritter/sheep/sheep", "sheep");
	}

	/** How long one animal may take (walk + milk) before it is given up on. */
	private static final int MILK_TIMEOUT = 45000;

	private static volatile Thread running = null;

	private final GameUI gui;
	private final Coord2d aul, abr; // animal area
	private final Coord2d bul, bbr; // barrel area

	/** The barrel being carried and where it came from. */
	private long barrelId = -1;
	private Coord2d barrelHome = null;
	private double barrelHomeA = 0;
	/** Barrels that were filled (or found unusable) this run; never picked up again. */
	private final Set<Long> usedBarrels = new HashSet<>();
	/** The server's error for the last animal that couldn't be milked. */
	private String lastFailure = null;
	private String lastSkipReason = null;

	private AreaMilker(GameUI gui, Coord2d aul, Coord2d abr, Coord2d bul, Coord2d bbr) {
		this.gui = gui;
		this.aul = aul;
		this.abr = abr;
		this.bul = bul;
		this.bbr = bbr;
	}

	// ---------------------------------------------------------------------------------------
	// UI thread
	// ---------------------------------------------------------------------------------------

	/**
	 * The "Milk Animals in Area" menu-grid button. First the player drags a rectangle over the
	 * animals, then one over the empty barrels. Pressing the button while it runs stops it.
	 */
	public static void menuButton(GameUI gui) {
		if (gui == null || gui.map == null)
			return;
		if (isRunning()) {
			stop(gui, true);
			return;
		}
		if (gui.vhand != null) {
			gui.error("Put down the item on your cursor first.");
			return;
		}
		boolean started = gui.map.selectAreaClientSide((a, b) -> animalAreaSelected(gui, a, b));
		if (started)
			gui.msg("Milking: drag a rectangle over the cows, goats and sheep to milk. Right-click cancels.", Color.WHITE);
		else
			gui.error("Another area selection is already active.");
	}

	private static void animalAreaSelected(GameUI gui, Coord tul, Coord tbr) {
		Coord2d aul = tileToWorld(tul), abr = tileToWorld(tbr);
		int n = 0;
		for (Gob g : Utils.getAllGobs(gui)) {
			if (inRect(g.rc, aul, abr) && species(g) != null)
				n++;
		}
		if (n == 0) {
			gui.error("No cows, nanny goats or sheep in that area.");
			return;
		}
		boolean started = gui.map.selectAreaClientSide((a, b) -> barrelAreaSelected(gui, aul, abr, a, b));
		if (started)
			gui.msg("Now drag a rectangle over the empty barrels. Right-click cancels.", Color.WHITE);
		else
			gui.error("Another area selection is already active.");
	}

	private static void barrelAreaSelected(GameUI gui, Coord2d aul, Coord2d abr, Coord tul, Coord tbr) {
		AreaMilker bot = new AreaMilker(gui, aul, abr, tileToWorld(tul), tileToWorld(tbr));
		Gob carried = carriedBy(gui);
		if (carried != null && !BARREL.equals(resName(carried))) {
			gui.error("You're carrying something else. Put it down first.");
			return;
		}
		if (carried == null && bot.nearestEmptyBarrel() == null) {
			gui.error("No empty barrel in that area.");
			return;
		}
		int animals = bot.animals(new HashSet<>(), null).size();
		stop(gui, false);
		Thread t = new Thread(bot, "AreaMilker");
		running = t;
		gui.msg("Milking " + animals + " animal" + (animals == 1 ? "" : "s") + ". Press the Milk Animals button again to stop.", Color.WHITE);
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
				gui.msg("Stopped milking.", Color.WHITE);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Bot thread
	// ---------------------------------------------------------------------------------------

	private enum Outcome {MILKED, BARREL_FULL, FAILED}

	@Override
	public void run() {
		int milked = 0, barrelsFilled = 0;
		Set<Long> done = new HashSet<>();
		Set<Long> failed = new HashSet<>();
		boolean outOfBarrels = false;
		try {
			Gob player = gui.map.player();
			if (player == null)
				return;
			if (player.getattr(Following.class) != null) {
				gui.error("Get off the vehicle first.");
				return;
			}
			// A barrel already in the player's arms is used first and set down at the end where the player stands.
			Gob carried = carriedBy(gui);
			if (carried != null) {
				barrelId = carried.id;
				barrelHome = null;
			}

			outer:
			for (String sp : new LinkedHashSet<>(ANIMALS.values())) {
				// A fresh barrel for every species so milks don't mix.
				if (barrelId >= 0 && hasContents(barrelId)) {
					putBarrelBack();
					barrelsFilled++;
				}
				int triesThisAnimal = 0;
				long lastAnimal = -1;
				while (!Thread.currentThread().isInterrupted()) {
					Gob a = nearest(animals(union(done, failed), sp));
					if (a == null)
						break;
					if (barrelId < 0 || !isCarried(barrelId)) {
						barrelId = -1;
						if (!pickUpBarrel()) {
							outOfBarrels = true;
							break outer;
						}
					}
					if (a.id == lastAnimal) {
						// Two fresh barrels in a row "full" for the same animal: give up on it.
						if (++triesThisAnimal > 2) {
							failed.add(a.id);
							continue;
						}
					} else {
						lastAnimal = a.id;
						triesThisAnimal = 0;
					}
					Outcome o = milk(a);
					if (o == Outcome.MILKED) {
						milked++;
						done.add(a.id);
					} else if (o == Outcome.BARREL_FULL) {
						putBarrelBack();
						barrelsFilled++;
					} else {
						failed.add(a.id);
						if (lastFailure != null)
							lastSkipReason = lastFailure;
					}
				}
			}
			StringBuilder sb = new StringBuilder("Done milking: " + milked + " animal" + (milked == 1 ? "" : "s"));
			if (barrelsFilled > 0)
				sb.append(", ").append(barrelsFilled).append(" barrel").append(barrelsFilled == 1 ? "" : "s").append(" put back");
			if (!failed.isEmpty())
				sb.append(", ").append(failed.size()).append(" skipped").append(lastSkipReason != null ? " (last reason: " + lastSkipReason + ")" : "");
			sb.append(".");
			gui.msg(sb.toString(), Color.WHITE);
			if (outOfBarrels)
				gui.error("Out of empty barrels in the barrel area. Some animals weren't milked.");
		} catch (InterruptedException ignored) {
		} catch (Exception e) {
			gui.error("Milking stopped: " + e.getMessage());
		} finally {
			if (barrelId >= 0) {
				Thread.interrupted(); // clear a stop request so the barrel still goes back
				try {
					putBarrelBack();
				} catch (InterruptedException ignored) {
				}
			}
			if (running == Thread.currentThread())
				running = null;
		}
	}

	/** Lifts the nearest empty barrel in the barrel area. */
	private boolean pickUpBarrel() throws InterruptedException {
		for (int i = 0; i < 3; i++) {
			Gob b = nearestEmptyBarrel();
			if (b == null)
				return false;
			Coord2d home = b.rc;
			double homeA = b.a;
			gui.wdgmsg("act", "carry");
			clickGob(b, 1, 0);
			if (waitFor(() -> isCarried(b.id), 30000)) {
				barrelId = b.id;
				barrelHome = home;
				barrelHomeA = homeA;
				return true;
			}
			resetCursor();
			usedBarrels.add(b.id); // couldn't lift this one; try another
		}
		return false;
	}

	/** Sets the carried barrel down where it came from (or where the player stands). */
	private void putBarrelBack() throws InterruptedException {
		long id = barrelId;
		barrelId = -1;
		if (id < 0)
			return;
		usedBarrels.add(id);
		if (!isCarried(id))
			return;
		Coord2d pos = barrelHome;
		if (pos == null) {
			Gob player = gui.map.player();
			if (player == null)
				return;
			pos = player.rc;
		}
		if (!place(id, pos, barrelHomeA))
			gui.error("Couldn't set the barrel back down.");
		barrelHome = null;
	}

	/**
	 * Shift+Right-Clicks the animal with the barrel carried and waits for the server's answer: an
	 * error (one about being full means the barrel is full), or the character standing still again
	 * without one, which counts as milked.
	 */
	private Outcome milk(Gob animal) throws InterruptedException {
		final int errSeq = gui.ui.serverErrorSeq;
		final int infoSeq = gui.ui.serverInfoSeq;
		final int sfxSeq = gui.ui.serverSfxSeq;
		boolean hadMilk = hasContents(barrelId);
		lastFailure = null;
		clickGob(animal, 3, UI.MOD_SHIFT);

		Integer ping = GameUI.getPingValue();
		long stall = Math.max(2500, (ping != null ? ping : 100) * 6L);
		long start = System.currentTimeMillis();
		long idleSince = -1;
		while (true) {
			if (gui.ui.serverErrorSeq != errSeq) {
				String t = gui.ui.lastServerError;
				if (saysFull(t))
					return Outcome.BARREL_FULL;
				lastFailure = t;
				return Outcome.FAILED;
			}
			if (gui.ui.serverInfoSeq != infoSeq && saysFull(gui.ui.lastServerInfo))
				return Outcome.BARREL_FULL;
			if (gui.ui.serverSfxSeq != sfxSeq) {
				String s = gui.ui.lastServerSfx;
				if (s != null && (s.contains("water") || s.contains("milk") || s.contains("pour")))
					return settle(errSeq, infoSeq);
			}
			if (!hadMilk && hasContents(barrelId))
				return settle(errSeq, infoSeq);
			if (!isCarried(barrelId))
				return Outcome.FAILED;
			long now = System.currentTimeMillis();
			if (now - start > MILK_TIMEOUT)
				return Outcome.FAILED;
			Gob player = gui.map.player();
			boolean busy = (player != null && player.getv() > 0) || gui.prog != null;
			if (busy)
				idleSince = -1;
			else if (idleSince < 0)
				idleSince = now;
			else if (now - idleSince > stall)
				return Outcome.MILKED;
			Thread.sleep(50);
		}
	}

	/** After a sign of success, gives the server a moment to still say the barrel ran full. */
	private Outcome settle(int errSeq, int infoSeq) throws InterruptedException {
		long until = System.currentTimeMillis() + 400;
		while (System.currentTimeMillis() < until) {
			if ((gui.ui.serverErrorSeq != errSeq && saysFull(gui.ui.lastServerError))
					|| (gui.ui.serverInfoSeq != infoSeq && saysFull(gui.ui.lastServerInfo)))
				return Outcome.BARREL_FULL;
			Thread.sleep(50);
		}
		return Outcome.MILKED;
	}

	private static boolean saysFull(String text) {
		if (text == null)
			return false;
		String t = text.toLowerCase(Locale.ROOT);
		return t.contains("full") || t.contains("no room") || t.contains("no space");
	}

	/** Sets a carried object down at {@code pos}, facing {@code a}. */
	private boolean place(long id, Coord2d pos, double a) throws InterruptedException {
		Gob player = gui.map.player();
		if (player == null)
			return false;
		gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, UI.MOD_SHIFT, 0, (int) id, player.rc.floor(posres), 0, -1);
		if (!waitFor(gui.map::isPlacingReady, 5000))
			return false;
		gui.map.wdgmsg("place", pos.floor(posres), (int) Math.round(a * 32768 / Math.PI), 1, UI.MOD_SHIFT);
		return waitFor(() -> !isCarried(id), 30000);
	}

	// ---------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------

	/** Milkable animals of {@code sp} (any species when null) in the animal area, minus {@code skip}. */
	private List<Gob> animals(Set<Long> skip, String sp) {
		List<Gob> res = new ArrayList<>();
		for (Gob g : Utils.getAllGobs(gui)) {
			if (skip.contains(g.id) || !inRect(g.rc, aul, abr))
				continue;
			String s = species(g);
			if (s == null || (sp != null && !sp.equals(s)))
				continue;
			res.add(g);
		}
		return res;
	}

	private Gob nearestEmptyBarrel() {
		List<Gob> res = new ArrayList<>();
		for (Gob g : Utils.getAllGobs(gui)) {
			if (usedBarrels.contains(g.id) || !BARREL.equals(resName(g)) || !inRect(g.rc, bul, bbr))
				continue;
			if (g.getattr(Following.class) != null || hasContents(g))
				continue;
			res.add(g);
		}
		return nearest(res);
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

	private static String species(Gob g) {
		String n = resName(g);
		return (n == null) ? null : ANIMALS.get(n);
	}

	private boolean hasContents(long id) {
		Gob g = gob(id);
		return g != null && hasContents(g);
	}

	/** A barrel shows its contents as a {@code gfx/terobjs/barrel-<liquid>} overlay; none = empty. */
	private static boolean hasContents(Gob barrel) {
		synchronized (barrel.ols) {
			for (Gob.Overlay ol : barrel.ols) {
				if (ol.getSprResName().startsWith("gfx/terobjs/barrel-"))
					return true;
			}
		}
		return false;
	}

	private static Set<Long> union(Set<Long> a, Set<Long> b) {
		Set<Long> res = new HashSet<>(a);
		res.addAll(b);
		return res;
	}

	private static Coord2d tileToWorld(Coord t) {
		return new Coord2d(t.x * MCache.tilesz.x, t.y * MCache.tilesz.y);
	}

	private static boolean inRect(Coord2d c, Coord2d ul, Coord2d br) {
		return c.x >= ul.x && c.x < br.x && c.y >= ul.y && c.y < br.y;
	}

	private Gob gob(long id) {
		return gui.map.glob.oc.getgob(id);
	}

	private void clickGob(Gob gob, int button, int modflags) {
		gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), button, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
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

	private boolean isCarried(long gobid) {
		if (gobid < 0)
			return false;
		Gob g = gob(gobid);
		if (g == null)
			return false;
		Following f = g.getattr(Following.class);
		return f != null && f.tgt == gui.map.plgob;
	}

	private void resetCursor() {
		try {
			if (carriedBy(gui) == null && !gui.ui.checkCursorImage("gfx/hud/curs/arw")) {
				Gob player = gui.map.player();
				if (player != null)
					gui.map.wdgmsg("click", Coord.z, player.rc.floor(posres), 3, 0);
			}
		} catch (Exception ignored) {
		}
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
		try {
			Resource res = g.getres();
			return res != null ? res.name : null;
		} catch (Loading l) {
			return null;
		}
	}
}
