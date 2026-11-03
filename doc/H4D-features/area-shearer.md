# Shear sheep and goats in an area

Menu-grid button *Shear Animals in Area* (Other Scripts & Tools, `MenuGrid.useCustom` →
`AreaShearer.menuButton`). One `MapView.selectAreaClientSide` rectangle; pressing the button while
it runs stops it. Code: `automated/AreaShearer`, own thread (static `running`). Icon = the game's
`gfx/invobjs/wool` + `gfx/invobjs/shears` PNGs (copied from the mapper server's `wwwroot/gfx`),
composited at 128×128 into the image layer of `LightFiresInArea.res` (same `scale 4.0` header).

## Tool

- Searched in the main inventory and the character's own container windows
  (`GItem.ContentsWindow.myOwnEquipory`: belt, backpack, pouches), plus the two hand slots
  (`Equipory.slots[6/7]`).
- Shears already in a hand → kept. Else the highest-quality `gfx/invobjs/shears` carried. Else the
  highest-quality axe held or carried (basenames `axe-m`, `stoneaxe`, `woodsmansaxe`,
  `tinkersthrowingaxe`, `b12axe`; pickaxe excluded). No tool at all → shearing is tried anyway.
- Swap: two different items in hand → the left-hand one is taken and stashed first. Then
  `take` the tool → `Equipory.wdgmsg("drop", slot)`; anything the swap puts on the cursor (e.g. a
  two-hander) is dropped back where the tool was, else anywhere there's room. The tool is not put
  away afterwards.

## Shearing loop

Targets: gobs whose resource starts with `gfx/kritter/sheep` or `gfx/kritter/goat` and doesn't
contain `wild` (Nurgling's filter), each tried once, nearest first. Per animal: RMB (server walks)
→ wait for a `FlowerMenu` under `ui.root` → choose the option starting with "Shear" (else close
the menu = no wool) → wait until the character has been idle (no movement, no progress bar, no
`carving` pose) ~3 s. Stops when fewer than 2 free main-inventory squares are left.

## Untested assumptions (cloud build, no in-game run)

The option name ("Shear wool" from Nurgling), the `carving` pose and that an axe can shear were
not checked in game here.
