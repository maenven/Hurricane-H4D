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
- **Fuel:** ore smelter (sdt bit `0b0010`) and stack furnace (bit `0b0001`) are known to be lit or not
  (bits from Ender's `GobTag`); while unlit and in view their timers are pushed back and show "(unlit)".
  Other objects cannot be checked, so a timer there keeps running if the fire goes out.
- Label: next item's time, plus "(all …)" when the last one is ≥1 min later; green "Some done, all in …"
  / "Done" afterwards. `ProcessTimerGobInfo` is attached in `Gob.init` beside the other `GobInfo`s.
