# Light fires in an area (candelabrum / torchpost)

Ctrl+Right-Click on a lit candelabrum (`gfx/terobjs/candelabrum`) or a torchpost
(`gfx/terobjs/torchpost`) holding a lit torch opens a one-entry `ClientMenu` ("Light fires in area
with this …"), then `MapView.selectAreaClientSide`. Code: `automated/AreaFireLighter`. Ported from
Nurgling2's `nurgling/actions/bots/LightObject`. Runs on its own thread (static `running`).

## State read from draw data

"Marker" = `ResDrawable.sdt` bytes 0..3 little-endian (Nurgling's `calcMarker` / `getModelAttribute`).

| Resource | Fire bit | Fuel bits (0 = not shown) | Embers value |
|---|---|---|---|
| kiln | 1 | – | – |
| smokeshed | 16 | – | – |
| oven | 4 | – | – |
| pow (fireplace) | 4 | 1 | 11 |
| tarkiln | 16 | – | – |
| cauldron | 2 | 1 | – |
| brazier | 8 | 1 | – |
| smelter | 2 | – | – |
| primsmelter (stack furnace) | 6 (either bit) | – | – |
| fineryforge | 8 | 4 | – |
| steelcrucible | 4 | – | – |
| crucible | 4 | 3 | – |

Lit candelabrum = marker exactly 3. Torchpost: an overlay whose sprite class is `Equed`; its
`OlSprite.sdt` last byte 1 = lit torch.

## Flow

1. Fireplaces on embers: flower option *Light My Fire* (no source needed).
2. Candelabrum: `act carry` + LMB on it (lift), then **Shift+RMB** each target while carrying.
   Torchpost: flower *Take torch*, then `itemact` on each target.
3. Nearest first. A target counts as failed after the character has been idle (not moving, no
   progress bar) for ~3 s without the fire bit, or 45 s total.
4. Put back: the candelabrum is placed at its original position/angle (Shift+RMB on it → place);
   the torch goes back with `itemact` on the post. Also runs when stopped.

## Untested assumptions (cloud build, no in-game run)

All the mechanics above are from Nurgling and were not checked in game here. Ovens/kilns/smoke sheds
show no fuel bit, so an unfuelled one is attempted and reported as "didn't catch".
