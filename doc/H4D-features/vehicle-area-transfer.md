# Cart/wagon load and unload from an area

Ctrl+Right-Click on a cart (`gfx/terobjs/vehicle/cart`) or wagon (`gfx/terobjs/vehicle/wagon`)
opens a client-side menu (`ClientMenu`, looks like the vertical flower menu but never talks to the
server). Ported from Nurgling2's `LoadVehicleAction` / `UnloadVehicleAction`
(`nurgling/contextmenu/`), rebuilt on Hurricane's own pieces. Code: `automated/VehicleAreaTransfer`.

## Flow

1. `MapView.Click.hit` → `VehicleAreaTransfer.ctrlRightClick(gui, gob)` (UI thread). Returns false
   (click goes to the server as before) when the option is off, an item is on the cursor, or the
   player is carrying an object. While a run is active the menu offers only *Stop*.
2. The choice starts `MapView.selectAreaClientSide(cb)`: a normal `Selector` with `clientCallback`
   set. Mouse-up hands the tile rectangle (ul inclusive, br exclusive) to the callback and does
   **not** send `"sel"`; right-click cancels. Refused when a server selection is open.
3. Load: the area's gobs are grouped by resource (obvious non-liftables filtered out, live animals
   too — only knocked ones), and a second `ClientMenu` picks the kind when there are several.
4. The work runs on `GameUI.vehicleAreaTransferThread`.
5. A third choice, *Fill barrels at a well/cistern/mill*, hands over to `VehicleBarrelFiller` (see [vehicle-barrel-filler.md](vehicle-barrel-filler.md)).

## Server mechanics used (mirrors Nurgling)

- **Lift:** `act carry` + LMB click on the gob. Carried = a gob whose `Following.tgt` is the player.
- **Store:** RMB on the vehicle while carrying. No ack → treated as full; the object is put back
  where it came from.
- **Cart contents:** `ResDrawable.sdt` byte 0, bits 2..7 = the six slots. Take slot *i* out with a
  RMB click whose last arg (mesh id) is `i + 2`.
- **Wagon contents:** flower option "Open" → window "Wagon"; its non-`Inventory` child with ≥ 20
  children is the cargo; click (`UI.scale(15,15)`, button 1) a child that isn't the resource class
  `InvSquare` (matched by simple class name) to lift it out.
- **Set down:** Shift+RMB on the carried gob gives the placement ghost (`MapView.isPlacingReady`),
  then `wdgmsg("place", pos, angle, 1, MOD_SHIFT)`.
- The server walks the character for every one of these; waits give up after 30 s or when the
  character has stood still ~2 s without the expected change.

## Free spots

`freeSpot` scans the area in 1-unit steps from the top-left corner, placing unrotated. Obstacles are
`HitBoxes.collisionBoxMap` boxes of every other gob (rotated, taken as an axis-aligned box, mirrored
in y because Neg-derived boxes are stored with y flipped) plus a 1-unit gap. Players and anything
being carried are ignored. Unknown hitbox → one tile.

## Untested assumptions (cloud build only, no in-game run)

The wagon window title/structure and the "Open" option come from Nurgling and were not checked here.
