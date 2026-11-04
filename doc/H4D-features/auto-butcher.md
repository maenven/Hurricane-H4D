# Auto Butcher

`haven.automated.AutoButcher` (a `Window`), opened by the menu-grid button *Auto Butcher*
(Other Scripts & Tools; pressing it again closes the window and stops a run).

## Settings (prefs)

- **Stat** (`autoButcherStat`): every stat of `AnimalStatsOverlay.HIGHLIGHTS` except "No highlight"
  (milk/meat/wool amount, Quality, Breeding, milk/meat/hide/wool quality %). Values come from
  `AnimalStatsOverlay.value(entry, stat)`, i.e. the Cattle Roster entry, so the roster must have been
  opened once this session. An animal that doesn't have the stat (wool on a cow, milk on a pig) is skipped.
- **Butcher below** (`autoButcherLimit`): strictly less than.
- **Genders** (`autoButcherGender`): male flag read by reflection from the first existing field of
  `bull, billy, ram, hog, stallion, buck`; young from `calf, kid, lamb, piglet, foal, fawn`
  (*Skip young animals*, `autoButcherSkipYoung`, default on). Field names from Nurgling2's local copies
  of the server roster classes (`gfx/hud/rosters/*`).
- *Butcher the carcasses* (`autoButcherCarcass`), *Remove them from the Cattle Roster*
  (`autoButcherRosterRemove`, sends the roster's own `rm` message), both default on.

## Flow

1. *Select area* → `MapView.selectAreaClientSide`; `preview()` lists gobs in the rectangle with a
   `CattleId` whose entry passes the filters. They get a red `KillMark` tint (set/removed in the window's
   `tick`, UI thread). Changing the stat/limit/genders re-runs the preview.
2. *Start* (bot thread): equip the best sharp tool once (`CuttingToolAutoEquip.equipBest`, SHARP), butcher
   carcasses already lying in the area (resume after an inventory-full stop), then per target, nearest
   first: right-click → *Slaughter* → wait until the gob has a `knock`/`dead` pose → roster `rm` →
   butcher: right-click the carcass, pick the first present of Skin, Scale, Crack, Clean, Butcher,
   Collect bones (Nurgling2's order), wait until idle, repeat until no option is left or the gob is gone.
   An item left on the cursor goes into the inventory, else on the ground.
3. Before each carcass step the main inventory must have room (Nurgling2's sizes: Skin 2x2, Butcher 2× 1x1,
   …); otherwise the run stops with "inventory full".

## Untested assumptions (cloud build, no in-game run)

The option name "Slaughter" and the `knock` pose come from Nurgling2; the carcass option names from its
`Butcher` bot. Not checked in game here.
