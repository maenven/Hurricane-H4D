# Animal stats above heads

`haven.automated.AnimalStatsOverlay`. Off by default.

- **Data source:** the Cattle Roster (`haven.res.ui.croster`). The gob carries a `CattleId` attrib
  (server objdelta) whose `entry()` finds the matching `Entry` in `RosterWindow.rosters`. Entries
  exist only after the roster has been opened once this session (the server sends the list then;
  closing only hides the window).
- **Per-species entry classes are server code** (`gfx/hud/rosters/cow` → `Ochs`, `Goat`, `Sheep`,
  `Horse`, `Pig`, `Teimdeer`; local copies exist in Nurgling2). Fields `seedq`, `milkq`, `meatq`,
  `hideq`, `woolq` are read by reflection (`value()`); `q` is on the local `Entry`. milkq/meatq/... are
  the roster's percentages, shown as `%`. Milk is hidden for `Horse`/`Pig`.
- **Label:** two lines above the name: amounts (`milk`, `meat`, `wool` fields; Ian: quantity matters
  more than quality) on top, qualities (`Q`, `B`, `MQ %`, plus the highlighted stat if not shown) below.
- **Drawing:** `CattleId.draw` (render thread) draws the stats line above the name; the name is now also
  drawn while the overlay is on, not only while the roster window is visible.
- **Highlight:** `GameUI.tick` → `AnimalStatsOverlay.tick` every 0.5 s on the UI thread computes a color
  per animal id per roster (needs ≥ 2 animals with the stat): best mode = green for the max (ties all win);
  Heatmap mode (pref `animalStatsHeatmap`) = `heat((v-min)/(max-min))`, green → yellow → red, quantized
  to 10 steps so the `ColorMask`s/textures are reused. Computed over *all* roster entries, not just
  visible gobs, and sets/removes the `BestHighlight` SetupMod under `synchronized(gob)`. Never from
  `GAttrib.ctick`: `Gob.ctick` iterates the attr map, so setattr there throws CME.
