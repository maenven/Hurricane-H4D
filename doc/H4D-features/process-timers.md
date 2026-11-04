# Smelter / oven finish timers

Countdown above smelters, stack furnaces, finery forges, ovens, kilns, steel crucibles, crucibles,
smoke sheds and tar kilns, like Ender's and Kami's clients. Option: *Show Smelter/Oven Timers*
(Display settings, next to *Show Produce Sack Text*; pref `showProcessTimers`, default on).

## How it works (`src/haven/ProcessTimers.java`, `src/haven/ProcessTimerGobInfo.java`)

- The server only sends item progress (the inventory items' `MeterInfo`) while the object's window is
  open. So the timer is computed while you look inside, stored per gob id with absolute end times
  (`Utils.rtime()`), and counts down on its own after the window closes. In memory only (lost on
  restart); forgotten an hour after everything finished.
- **Window → gob link:** `GameUI.addchild` passes the window's `"obj"` option (the gob the server
  opened it from) to `ProcessTimers.windowOpened`; fallback is the last right-clicked gob within 5 s
  (`MapView` calls `noteInteract`). Only resources in `PROCESSORS` are tracked.
- **Durations:** each item's meter is polled every tick (`GameUI.tick`). Two observed meter changes
  give the item's full processing time (Δt / Δmeter). Learned times are averaged and saved as prefs
  `proctime/<gobres>/<itemres>` (seconds), so later looks get a timer at once. Seeds: ore smelter and
  stack furnace 55 min, finery forge 9 min (from Ender). With no time yet, the label shows the percent.
- **Fuel bar (ovens, kilns):** baked goods have no progress meter, so ovens are timed from fuel instead.
  The window's fuel bar is a `VMeter` (meter colour 255,128,0, value = share of a full load). A full
  oven/kiln holds 30 units (Nurgling2 `Container.FuelLvl`, 1 branch = 1 unit) and one unit burns
  15 in-game minutes ≈ 273.6 real s (Ring of Brodgar Kiln table: 8 units = 36:29 real). Fire timer =
  fuel × 30 × 273.6 s. Bar drops seen while the window is open refine it (pref `procfuel/<gobres>`, seconds
  per full bar), which also gives other objects a fire timer once learned.
- **Oven baking:** baked goods need 4 units (wiki). Dough that appears in the oven while its window is open
  gets "Baked in" = 4 × 273.6 s ≈ 18 min; dough already inside when you open it only gets the fire timer.
- **Unlit:** fire bits per object in `FIRE_BIT` (Nurgling2's LightObject, same as Light Fires in Area;
  smelter `2`, stack furnace `1` from Ender). While an object in view is unlit, all its timers are pushed
  back and show "(unlit)".
- Label (parts joined by " · "): next item's time, plus "(all …)" when the last one is ≥1 min later; green "Some done, all in …"
  / "Done" afterwards; "Baked in …" / "Baked"; "Fire …" / "Fire out". `ProcessTimerGobInfo` is attached in `Gob.init` beside the other `GobInfo`s.
