# Fill barrels from a vehicle at a well / cistern / mill

Menu-grid button *Fill Barrels from Vehicle* (Other Scripts & Tools, `MenuGrid.useCustom` →
`VehicleBarrelFiller.menuButton`). Port of Nurgling2's `contextmenu/FillBarrelsFromVehicleAction`
+ `actions/TakeFromVehicleSlot`. Pressing the button while it runs stops it.

## Picking

`MapView.startClientGobPick(cb)`: the next left-click on a gob goes to `cb` instead of the server
(hooked at the top of `MapView.Click.hit`); left-clicks on bare ground are swallowed; right-click
cancels. One pick for the source (anything that isn't a vehicle), then one for the vehicle. A menu
button was chosen over Ctrl+Right-Click on the source because Ctrl+RMB already means something on
some objects (e.g. candelabra).

## Vehicles

| Resource | Cargo access |
|---|---|
| `gfx/terobjs/vehicle/cart` | `ResDrawable.sdt` byte 0 bits 2..7; RMB with mesh id `slot + 2` lifts slot |
| `gfx/terobjs/vehicle/wagon` | flower "Open" → window "Wagon", cargo widget ≥ 20 children |
| `gfx/terobjs/vehicle/snekkja` | flower "Cargo" → window "Snekkja", ≥ 16 |
| `gfx/terobjs/vehicle/knarr` | flower "Cargo" → window "Knarr", ≥ 64 |

Window vehicles: the window is closed and reopened before each take (it closes on its own when the
character walks off); objects are counted once and taken by index among non-`InvSquare` children.

## Per object

Take it out → if it is `gfx/terobjs/barrel` with no `gfx/terobjs/barrel-*` overlay, Shift+RMB the
source and wait (≤ 60 s, stall detection) for an overlay or a server error (`UI.serverErrorSeq`) →
RMB the vehicle to put it back. Non-barrels and non-empty barrels go straight back. Summary line
at the end: filled / already had something / couldn't be filled (+ last server error).

## Untested assumptions (cloud build, no in-game run)

- Shift+RMB with a carried barrel fills it at a well (Nurgling's `activateGob`); not checked for
  cisterns or a milling machine.
- The milling machine sits inside the windmill, and vehicles can't go indoors, so with the vehicle
  outside the server can't walk between them — mill flour only works where both are reachable.
- Snekkja/knarr window titles and flower options come from Nurgling.
