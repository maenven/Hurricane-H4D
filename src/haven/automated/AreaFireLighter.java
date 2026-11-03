package haven.automated;

import haven.*;
import haven.automated.helpers.ClientMenu;

import java.awt.Color;
import java.util.*;
import java.util.function.BooleanSupplier;

import static haven.OCache.posres;

/**
 * Ctrl+Right-Click on a lit candelabrum or a torchpost holding a lit torch, drag out an area, and
 * every kiln, smoke shed, oven, fireplace (and the other fire workstations below) in it that is not
 * burning yet gets lit with that light source. The source is put back where it was afterwards.
 *
 * Ported from Nurgling2's unified lighter ({@code nurgling/actions/bots/LightObject}): the fire bits
 * per workstation, the "lit candelabrum = draw data 3" rule, Shift+Right-Click with a carried
 * candelabrum to light, and "Take torch" / item-click to light with a torch all come from there.
 * Like the cart/wagon area transfer, the server walks the character for every click.
 */
public class AreaFireLighter implements Runnable {
	public static final String CANDELABRUM = "gfx/terobjs/candelabrum";
	public static final String TORCHPOST = "gfx/terobjs/torchpost";

	/** What a workstation looks like: its fire bit, its fuel bits (0 = not visible) and its embers state (-1 = none). */
	private static final class Kind {
		final String name;
		final int fireFlag, fuelFlag, embers;

		Kind(String name, int fireFlag, int fuelFlag, int embers) {
			this.name = name;
			this.fireFlag = fireFlag;
			this.fuelFlag = fuelFlag;
			this.embers = embers;
		}
	}

	/** From Nurgling2's LightObject.getConfig. */
	private static final Map<String, Kind> KINDS = new LinkedHashMap<>();
	static {
		KINDS.put("gfx/terobjs/kiln", new Kind("Kiln", 1, 0, -1));
		KINDS.put("gfx/terobjs/smokeshed", new Kind("Smoke Shed", 16, 0, -1));
		KINDS.put("gfx/terobjs/oven", new Kind("Oven", 4, 0, -1));
		KINDS.put("gfx/terobjs/pow", new Kind("Fireplace", 4, 1, 11));
		KINDS.put("gfx/terobjs/tarkiln", new Kind("Tar Kiln", 16, 0, -1));
		KINDS.put("gfx/terobjs/cauldron", new Kind("Cauldron", 2, 1, -1));
		KINDS.put("gfx/terobjs/brazier", new Kind("Brazier", 8, 1, -1));
		KINDS.put("gfx/terobjs/smelter", new Kind("Ore Smelter", 2, 0, -1));
		KINDS.put("gfx/terobjs/primsmelter", new Kind("Stack Furnace", 6, 0, -1));
		KINDS.put("gfx/terobjs/fineryforge", new Kind("Finery Forge", 8, 4, -1));
		KINDS.put("gfx/terobjs/steelcrucible", new Kind("Steel Crucible", 4, 0, -1));
		KINDS.put("gfx/terobjs/crucible", new Kind("Crucible", 4, 3, -1));
	}

	/** How long one workstation may take (walk + light) before it is given up on. */
	private static final int LIGHT_TIMEOUT = 45000;

	private static volatile Thread running = null;

	private enum Source {CANDELABRUM, TORCHPOST}

	private final GameUI gui;
	private final long sourceId;
	private final Source source;
	private final Coord2d ul, br;

	private AreaFireLighter(GameUI gui, long sourceId, Source source, Coord2d ul, Coord2d br) {
		this.gui = gui;
		this.sourceId = sourceId;
		this.source = source;
		this.ul = ul;
		this.br = br;
	}

	// ---------------------------------------------------------------------------------------
	// UI thread
	// ---------------------------------------------------------------------------------------

	/** Called from MapView for Ctrl+Right-Click on a gob. True when the click was handled here. */
	public static boolean ctrlRightClick(GameUI gui, Gob gob) {
		if (gui == null || gob == null || gui.map == null)
			return false;
		if (OptWnd.areaFireLighterCheckBox == null || !OptWnd.areaFireLighterCheckBox.a)
			return false;
		String name = resName(gob);
		if (!CANDELABRUM.equals(name) && !TORCHPOST.equals(name))
			return false;
		final Source src = CANDELABRUM.equals(name) ? Source.CANDELABRUM : Source.TORCHPOST;
		final String srcName = (src == Source.CANDELABRUM) ? "candelabrum" : "torch";

		if (isRunning()) {
			gui.add(new ClientMenu(new String[]{"Stop lighting fires"}, idx -> stop(gui, true)), new Coord(-1, -1));
			return true;
		}
		if (gui.vhand != null)
			return false;
		Gob carried = carriedBy(gui);
		if (carried != null && carried.id != gob.id)
			return false; // carrying something else: leave the click alone

		if (src == Source.CANDELABRUM && !candelabrumLit(gob)) {
			gui.error("That candelabrum isn't lit.");
			return true;
		}
		if (src == Source.TORCHPOST) {
			Boolean lit = torchpostLit(gob);
			if (lit == null)
				return false; // no torch on it: let the click through as before
			if (!lit) {
				gui.error("The torch on that torchpost isn't lit.");
				return true;
			}
		}

		final long id = gob.id;
		gui.add(new ClientMenu(new String[]{"Light fires in area with this " + srcName}, idx -> {
			boolean started = gui.map.selectAreaClientSide((a, b) -> areaSelected(gui, id, src, a, b));
			if (started)
				gui.msg("Drag a rectangle over the kilns, smoke sheds, ovens and fireplaces to light. Right-click cancels.", Color.WHITE);
			else
				gui.error("Another area selection is already active.");
		}), new Coord(-1, -1));
		return true;
	}

	private static void areaSelected(GameUI gui, long sourceId, Source src, Coord tul, Coord tbr) {
		Coord2d ul = new Coord2d(tul.x * MCache.tilesz.x, tul.y * MCache.tilesz.y);
		Coord2d br = new Coord2d(tbr.x * MCache.tilesz.x, tbr.y * MCache.tilesz.y);
		AreaFireLighter bot = new AreaFireLighter(gui, sourceId, src, ul, br);
		List<Gob> todo = bot.targets(new HashSet<>());
		if (todo.isEmpty()) {
			gui.error("Nothing in that area needs lighting.");
			return;
		}
		stop(gui, false);
		Thread t = new Thread(bot, "AreaFireLighter");
		running = t;
		gui.msg("Lighting " + todo.size() + " fire" + (todo.size() == 1 ? "" : "s") + ". Ctrl+Right-Click the light source again to stop.", Color.WHITE);
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
				gui.msg("Stopped lighting fires.", Color.WHITE);
		}
	}

	// ---------------------------------------------------------------------------------------
	// Bot thread
	// ---------------------------------------------------------------------------------------

	@Override
	public void run() {
		Coord2d homeRc = null;
		double homeA = 0;
		boolean holding = false;
		try {
			Gob player = gui.map.player();
			if (player == null)
				return;
			if (player.getattr(Following.class) != null) {
				gui.error("Get off the vehicle first.");
				return;
			}
			Set<Long> failed = new HashSet<>();
			int lit = 0;

			// Fireplaces sitting on embers relight from a flower option; no light source needed.
			for (Gob t : targets(failed)) {
				Kind k = kind(t);
				if (k.embers < 0 || marker(t) != k.embers)
					continue;
				FlowerMenu.setNextSelection("Light My Fire");
				clickGob(t, 3, 0);
				if (waitLit(t.id, k))
					lit++;
				else
					failed.add(t.id);
			}
			if (targets(failed).isEmpty()) {
				report(lit, failed);
				return;
			}

			// Pick up the light source.
			Gob src = gob(sourceId);
			if (src == null) {
				gui.error("The light source is gone.");
				return;
			}
			if (source == Source.CANDELABRUM) {
				if (!isCarried(sourceId)) {
					homeRc = src.rc;
					homeA = src.a;
					gui.wdgmsg("act", "carry");
					clickGob(src, 1, 0);
					if (!waitFor(() -> isCarried(sourceId), 30000)) {
						resetCursor();
						gui.error("Couldn't pick up the candelabrum.");
						return;
					}
				}
			} else {
				FlowerMenu.setNextSelection("Take torch");
				clickGob(src, 3, 0);
				if (!waitFor(() -> gui.vhand != null, 30000)) {
					gui.error("Couldn't take the torch from the torchpost.");
					return;
				}
			}
			holding = true;

			// Light everything, nearest first.
			while (!Thread.currentThread().isInterrupted()) {
				if (source == Source.TORCHPOST && gui.vhand == null) {
					gui.error("The torch is no longer in hand.");
					holding = false;
					break;
				}
				if (source == Source.CANDELABRUM && !isCarried(sourceId)) {
					gui.error("The candelabrum is no longer carried.");
					holding = false;
					break;
				}
				Gob t = nearest(targets(failed));
				if (t == null)
					break;
				Kind k = kind(t);
				if (source == Source.CANDELABRUM)
					clickGob(t, 3, UI.MOD_SHIFT); // Shift+Right-Click with the candelabrum carried
				else
					itemact(t);
				if (waitLit(t.id, k))
					lit++;
				else
					failed.add(t.id);
			}
			report(lit, failed);
		} catch (InterruptedException ignored) {
		} catch (Exception e) {
			gui.error("Lighting fires stopped: " + e.getMessage());
		} finally {
			if (holding)
				putBack(homeRc, homeA);
			if (running == Thread.currentThread())
				running = null;
		}
	}

	private void report(int lit, Set<Long> failed) {
		if (failed.isEmpty())
			gui.msg("Done: lit " + lit + " fire" + (lit == 1 ? "" : "s") + ".", Color.WHITE);
		else
			gui.msg("Done: lit " + lit + ", " + failed.size() + " didn't catch (no fuel, or couldn't be reached).", Color.WHITE);
	}

	/** Returns the light source where it came from. Runs even when the bot was stopped. */
	private void putBack(Coord2d homeRc, double homeA) {
		try {
			Thread.interrupted(); // clear a stop request so the source still goes back
			if (source == Source.TORCHPOST) {
				Gob post = gob(sourceId);
				if (post != null && gui.vhand != null) {
					itemact(post);
					waitFor(() -> gui.vhand == null, 30000);
				}
				if (gui.vhand != null)
					gui.error("Couldn't put the torch back on the torchpost.");
			} else if (homeRc != null && isCarried(sourceId)) {
				if (!place(sourceId, homeRc, homeA))
					gui.error("Couldn't set the candelabrum back down.");
			}
		} catch (InterruptedException ignored) {
		}
	}

	/** Workstations in the area that are not burning, have visible fuel (where it shows) and haven't failed yet. */
	private List<Gob> targets(Set<Long> failed) {
		List<Gob> res = new ArrayList<>();
		for (Gob g : Utils.getAllGobs(gui)) {
			if (failed.contains(g.id) || !inArea(g.rc))
				continue;
			Kind k = kind(g);
			if (k == null || g.getattr(Following.class) != null)
				continue;
			long m = marker(g);
			if (m < 0 || (m & k.fireFlag) != 0)
				continue;
			if (k.embers >= 0 && m == k.embers) {
				res.add(g);
				continue;
			}
			if (k.fuelFlag != 0 && (m & k.fuelFlag) == 0)
				continue;
			res.add(g);
		}
		return res;
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

	/**
	 * Waits until the workstation's fire bit is set. Walking and the lighting progress bar count as
	 * busy; standing idle for a few seconds without the fire catching means it won't.
	 */
	private boolean waitLit(long id, Kind k) throws InterruptedException {
		BooleanSupplier lit = () -> {
			Gob g = gob(id);
			return g != null && (marker(g) & k.fireFlag) != 0;
		};
		Integer ping = GameUI.getPingValue();
		long stall = Math.max(3000, (ping != null ? ping : 100) * 6L);
		long start = System.currentTimeMillis();
		long idleSince = -1;
		while (true) {
			if (lit.getAsBoolean())
				return true;
			long now = System.currentTimeMillis();
			if (now - start > LIGHT_TIMEOUT)
				return false;
			Gob player = gui.map.player();
			boolean busy = (player != null && player.getv() > 0) || gui.prog != null;
			if (busy)
				idleSince = -1;
			else if (idleSince < 0)
				idleSince = now;
			else if (now - idleSince > stall)
				return lit.getAsBoolean();
			Thread.sleep(50);
		}
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

	private static Kind kind(Gob g) {
		String n = resName(g);
		return (n == null) ? null : KINDS.get(n);
	}

	/** The gob's draw data as one number, the way Nurgling2 reads its "model attribute". */
	static long marker(Gob g) {
		ResDrawable rd = g.getattr(ResDrawable.class);
		if (rd == null)
			return -1;
		byte[] b = rd.sdt.rbuf;
		if (b.length == 0)
			return -1;
		long res = 0;
		for (int i = Math.min(b.length, 4) - 1; i >= 0; i--)
			res = (res << 8) | (b[i] & 0xff);
		return res;
	}

	static boolean candelabrumLit(Gob g) {
		return marker(g) == 3;
	}

	/** True/false for a torchpost holding a lit/unlit torch, null when it holds none. */
	static Boolean torchpostLit(Gob post) {
		synchronized (post.ols) {
			for (Gob.Overlay ol : post.ols) {
				if (ol.spr != null && ol.spr.getClass().getSimpleName().equals("Equed") && ol.sm instanceof OCache.OlSprite) {
					byte[] sdt = ((OCache.OlSprite) ol.sm).sdt;
					return sdt != null && sdt.length > 0 && (sdt[sdt.length - 1] & 0xff) == 1;
				}
			}
		}
		return null;
	}

	private Gob gob(long id) {
		return gui.map.glob.oc.getgob(id);
	}

	private void clickGob(Gob gob, int button, int modflags) {
		gui.map.wdgmsg("click", Coord.z, gob.rc.floor(posres), button, modflags, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
	}

	private void itemact(Gob gob) {
		gui.map.wdgmsg("itemact", Coord.z, gob.rc.floor(posres), 0, 0, (int) gob.id, gob.rc.floor(posres), 0, -1);
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
