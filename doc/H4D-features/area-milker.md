# Milk animals in an area into barrels

Menu-grid button *Milk Animals in Area* (Other Scripts & Tools, `MenuGrid.useCustom` →
`AreaMilker.menuButton`). Two `MapView.selectAreaClientSide` rectangles: animals, then barrels.
Pressing the button while it runs stops it. Runs on its own thread (static `running`), like
`AreaFireLighter`. Mechanics from Nurgling2's `nurgling/actions/MilkAnimalsAction`.

## Targets

| Resource | Species |
|---|---|
| `gfx/kritter/cattle/cattle` | cow (bulls share the resource; the server refuses them) |
| `gfx/kritter/goat/nanny` | goat (billy/kid/wildgoat excluded) |
| `gfx/kritter/sheep/sheep` | sheep (rams share the resource; lambs excluded) |

Without the cattle roster the client can't see lactation, so every animal above is tried once.

Barrel = `gfx/terobjs/barrel`; empty = no `gfx/terobjs/barrel-<liquid>` overlay (same rule as
`BarrelContentsGobInfo`). Only empty barrels are picked up.

## Flow

1. Species in order cow → goat → sheep; a barrel that has milk in it is put back before the next
   species so milks never mix. A barrel already carried at start is used first and set down where
   the player stands.
2. Lift: `act carry` + LMB on the nearest empty barrel in the barrel area.
3. Milk: Shift+RMB on the nearest unvisited animal (Nurgling's `activateGob`, modflags 1).
4. Outcome, read from new `UI` fields fed by the server's `err` / `msg` / `sfx` uimsgs:
   - error or info text containing "full" / "no room" / "no space" → barrel full: place it back at
     its original spot and angle, lift the next empty one, retry the same animal (gives up on that
     animal after three fresh barrels in a row);
   - any other error → skip the animal (the last reason is shown in the summary);
   - a water/milk/pour sound, the barrel's milk overlay appearing, or the character standing idle
     ~2.5 s with no error → milked.
5. End / stop: the carried barrel goes back to its spot.

## Untested assumptions (cloud build, no in-game run)

The exact wording of the server's "barrel is full" and "nothing to milk" messages wasn't checked;
the "full" match is a guess. If a full barrel is reported differently, the animal is just skipped
instead of triggering a barrel swap.
