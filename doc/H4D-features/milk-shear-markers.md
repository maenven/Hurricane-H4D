# Milkable / shearable animal markers

`haven.automated.MilkShearMarkers`. Toggle: Custom Client Extras > Toggles > Milkable/Shearable Animal
Markers (pref `milkShearMarkers`, off by default). Two icons above the animal (above `CattleId`'s name/stats,
via `CattleId.drawnHeight()`):

- **Wool** (`gfx/invobjs/wool-sheep` / `wool-goat`): live, same signal as Ender's `GobTag.FLEECE` — a
  `Composite` model part (`comp.cmod`) whose name contains `-fleece`; shearing removes it. Reindeer/teimdeer
  have a fleece part too but can't be sheared, so they're excluded (Ian).
- **No milk** (`gfx/hud/rosters/milkquantity` droplet with a red X drawn on it): the server never sends how
  much milk an animal holds, and the roster `lactate` flag only means "produces milk". Earlier versions tried
  to show "has milk" by estimating refill and kept failing on what the client can't see; Ian's design
  (2026-11-04) is to show only "just emptied":
  - Applies to animals that can be milked: roster `lactate` (and not `dead`), or when no roster entry is
    loaded, an adult female by model part (`/cattle/cow`, `/sheep/ewe`, `/goat/nanny`).
  - Marked empty by: the Milk Animals button (`AreaMilker` → `noteMilked`); a manual attempt (any held item
    but shears used on it → `noteItemAct`; a right-click while carrying something, i.e. barrel overhead →
    `noteClick`; a "milk" flower option → `noteFlowerChoice`) followed within 30 s and before any error by
    (a) a sound on the animal/player/no gob (`AudioSprite` ClipSprite/RepeatSprite → `noteSound`) or a UI
    sfx whose name has milk/pour/water/... or anything but `kritter`; (b) an error mentioning milk but not
    "full"; (c) the character not starting to walk or work (`getv()`, `gui.prog`) within 2.5 s = refused.
    An error about a full container never marks it.
  - Cleared once 0.1 L should have refilled: roster `milk` quantity * 0.01 L per 10 min (wiki
    ringofbrodgar "Milk"; Ian: refills in 0.1 L steps, i.e. 100 / quantity minutes), or after 20 min when
    the quantity isn't known. Roster animals are saved in pref `milkShearMilkedAt` (`uidbits:epochms`,
    3 days); others by gob id for the session.
- `Marker` (GAttrib, Render2D) is attached in `Gob.init`; `ctick` recomputes every 0.5 s. Knocked/dead
  animals (`gob.knocked`) get nothing.
