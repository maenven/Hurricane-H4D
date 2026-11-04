# Milkable / shearable animal markers

`haven.automated.MilkShearMarkers`. Toggle: Custom Client Extras > Toggles > Milkable/Shearable Animal
Markers (pref `milkShearMarkers`, off by default). Icons, not words (Ian, 2026-11-04): milk =
`gfx/hud/rosters/milkquantity` (the roster's droplet, one icon for every milk type); wool =
`gfx/invobjs/wool-sheep` for sheep, `gfx/invobjs/wool-goat` (mohair) for goats. Loaded with
`Resource.remote().load` and scaled to 20 px; nothing is drawn until they have loaded.

- **Wool (live):** same signal as Ender's `GobTag.FLEECE`: the animal is a `Composite` whose current
  model parts (`comp.cmod`) include a resource whose name contains `-fleece`. Shearing removes that part,
  so the icon disappears by itself. Works on any sheep/goat in view, memorized or not.
- **Milk:** there is no model part for milk (Ender has no milk marker). The roster entry's `lactate`
  boolean (read by reflection, as Nurgling2's `MilkAnimalsAction` does) only means the animal **produces**
  milk (Ian: it stays on after milking). So the client remembers when it saw each animal milked
  (pref `milkShearMilkedAt` = `uidbits:epochms,...`, kept 3 days) and estimates the refill from the
  roster `milk` quantity: wiki (ringofbrodgar "Milk") says quantity * 0.01 L per 10 min, cap 25 L. The
  droplet shows once `MIN_LITERS` (0.1 L, the refill step per Ian; one step is enough to milk, e.g. for credo quests) has refilled; never-seen-milked animals count as having milk.
  - **Milking is detected** from: the Milk Animals button (`AreaMilker` MILKED outcome), or a manual
    attempt (any held item except shears used on a lactating animal → `MapView.iteminteract` → `noteItemAct`;
    a right-click on it while carrying something, i.e. a barrel over the head → `noteClick`; a flower-menu
    option containing "milk" → `noteFlowerChoice`) **confirmed by a sound** (Ian: the game plays one when
    milking succeeds; a full barrel/bucket gives an error and no sound). Within 30 s and before any server
    error: a positional sound (`AudioSprite.ClipSprite`/`RepeatSprite` ctor → `noteSound`) on the animal, the
    player or with no gob, or a UI `sfx` (`UI.lastServerSfx`), whose name has milk/pour/water/... or anything
    but `kritter` (the exact milking sound name is not known). An error mentioning "milk" but not "full"
    ("no milk") also counts. History (Ian, 2026-11-04): waiting for "idle + no error" missed bucket milking;
    marking on the click hid the droplet after a "barrel is full" attempt.
  - Only memorized animals, and only after the Cattle Roster has been opened this session.
- **Drawing:** `MilkShearMarkers.Marker` (GAttrib, Render2D) is attached in `Gob.init` to cattle, goats,
  sheep and reindeer. `ctick` recomputes every 0.5 s; `draw` anchors at the same point as `CattleId`
  (obj z=15) and moves up by `CattleId.drawnHeight()`, the pixels the name/stats used last frame.
- Knocked/dead animals (`gob.knocked`) get no marker.
