# Rock Crystal guard (block stone actions)

Option: Options → Gameplay Automation → *Block Stone Actions while carrying Rock Crystal*
(pref `blockStoneActionsWithRockCrystal`, default off).

## How it works

`RockCrystalGuard.blocks()` is called at the top of `UI.wdgmsg(sender, msg, args)`, the single
point every outgoing widget message passes through (player clicks *and* bots/scripts). If it
returns true the message is dropped, so the server never sees the action.

A message is dropped only when the option is on, a Rock Crystal is in `gui.maininv` (matched by
resource `gfx/invobjs/rockcrystal` or by the item name "Rock Crystal"), and the message is one of:

| Action | Message matched |
|---|---|
| Crafting (Craft / Craft All / Craft #) | `Makewindow` sends `make`, and any recipe input is stone: resource basename in `Config.stoneItemBaseNames`, `rockcrystal` or `stone` (item or its constraint), or the input's name contains "stone" / "rock crystal" |
| Building | a `Button` labelled `Build` sends `activate` (the construction-site window is server-defined, so *every* Build press is blocked, not only stone buildings) |
| Paving | `MenuGrid` sends `act` `landscape` (blocks the whole Landscape tool) |

A blocked craft also stops a running Craft # batch. The error message is throttled to once per
second so key-repeat on Enter doesn't spam it.

## Limits / not covered

- Only the main inventory is checked (not the belt, sacks or containers).
- Recipe stone detection is a name/resource heuristic; a stone input with an unexpected name would
  slip through.
- Building actions that aren't a "Build" button (e.g. a server window with a differently named
  button) aren't caught.
