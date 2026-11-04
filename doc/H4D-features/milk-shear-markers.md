# Milkable / shearable animal markers

`haven.automated.MilkShearMarkers`. Toggle: Custom Client Extras > Toggles > Milkable/Shearable Animal
Markers (pref `milkShearMarkers`, off by default).

- **Wool (live):** same signal as Ender's `GobTag.FLEECE`: the animal is a `Composite` whose current
  model parts (`comp.cmod`) include a resource whose name contains `-fleece`. Shearing removes that part,
  so the label disappears by itself. Works on any sheep/goat in view, memorized or not.
- **Milk (roster):** there is no model part for milk (Ender has no milk marker either). Nurgling2's
  `MilkAnimalsAction` uses the roster entry's `lactate` boolean, and so do we (by reflection, like
  `AnimalStatsOverlay`; `dead` excluded). Only memorized animals, and only after the Cattle Roster has
  been opened this session. It means "lactating", which may still be true right after a milking.
- **Drawing:** `MilkShearMarkers.Marker` (GAttrib, Render2D) is attached in `Gob.init` to cattle, goats,
  sheep and reindeer. `ctick` recomputes every 0.5 s; `draw` anchors at the same point as `CattleId`
  (obj z=15) and moves up by `CattleId.drawnHeight()`, the pixels the name/stats used last frame.
- Knocked/dead animals (`gob.knocked`) get no marker.
