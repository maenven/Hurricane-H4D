# H4D Features — Index

Small, single-topic files. Read only the ones relevant to the current task — do not bulk-read this folder.

| File | Read this when... |
|---|---|
| [upstream-merge-workflow.md](upstream-merge-workflow.md) | Pulling in a new NightDawg release / resolving merge conflicts with `upstream` (the 4-phase procedure). |
| [fork-customization-ledger.md](fork-customization-ledger.md) | Deciding what must survive an upstream sync, or recording a new intentional divergence. |
| [fork-maintenance-backlog.md](fork-maintenance-backlog.md) | Known deferred fork issues (release packaging, inherited workflows, tracked `.db` churn, update checker). Check before cutting a release. |
| [upstream-sync-history/](upstream-sync-history/) | What happened in past syncs (dated entries, e.g. [2026-10-09](upstream-sync-history/2026-10-09.md)). |
| [release-process.md](release-process.md) | Cutting a release of *this* fork for players (the `Create Release` Action, why `Release/` is stale/legacy). |
| [menugrid-system.md](menugrid-system.md) | Adding/changing anything in the bottom-right "Custom Client Extras" grid menu (new toggle, new bot, new script button). |
| [res-file-binary-format.md](res-file-binary-format.md) | Creating or editing a `.res` file by hand (icon/action/tooltip resource for the menu grid). |
| [chat-message-injection.md](chat-message-injection.md) | Sending a chat message from code without touching keyboard focus/UI (bots, scripts, alerts). |
| [yapper-bot.md](yapper-bot.md) | Working on the Yapper Bot feature specifically. |
| [group-permission-extension.md](group-permission-extension.md) | Touching `BuddyWnd.GroupSelector`, Kin/Village/claim/Field-Cairn permission groups, or `haven.groups.*`. |
| [portrait-performance.md](portrait-performance.md) | Lag/low FPS in big parties or fights, `Avaview` / Party widget / portraits, the "Disable 3D Portraits" option. |
| [auto-kin.md](auto-kin.md) | Auto Kin: sharing hearth secrets through the Web Map server, the `/kinSecret(s)` contract, `AutoKin` / `BuddyWnd` hooks. |
| [vehicle-area-transfer.md](vehicle-area-transfer.md) | Ctrl+Right-Click cart/wagon load/unload from an area: `VehicleAreaTransfer`, client-side `ClientMenu`, `MapView.selectAreaClientSide`. |
| [area-fire-lighter.md](area-fire-lighter.md) | *Light Fires in Area* menu button (candelabrum/torchpost → light kilns/ovens/smoke sheds/fireplaces in an area): `AreaFireLighter`, fire bits per workstation. |
| [area-shearer.md](area-shearer.md) | *Shear Animals in Area* menu button: tool choice (shears, else best axe), hand swap, flower-menu shearing loop in `AreaShearer`. |

| [area-milker.md](area-milker.md) | *Milk Animals in Area* menu button (animal area + barrel area → milk into barrels, swap when full): `AreaMilker`, server error/sound hooks on `UI`. |
| [craft-quantity.md](craft-quantity.md) | The crafting window's amount box / **Craft #** button (craft N items in a row) in `Makewindow`. |
| [rock-crystal-guard.md](rock-crystal-guard.md) | The *Block Stone Actions while carrying Rock Crystal* option: `RockCrystalGuard`, the `UI.wdgmsg` hook. |
| [village-member-sort.md](village-member-sort.md) | Village member list sort-by-group toggle and group filter (`Polity.MemberList`). |
| [barrel-take-amount.md](barrel-take-amount.md) | Barrel window amount box / **Take #** button (take N units of flour, salt, cave dust…): `BarrelTakeAmount`, `Window.barrelTakeTick`, stacking toggle. |
| [animal-stats-overhead.md](animal-stats-overhead.md) | Memorized animal stats (Q/B/M) above heads and best-stat green highlight: `AnimalStatsOverlay`, `CattleId`, Cattle Roster entries. |

Tools: [tools/New-MenuGridButton.ps1](tools/New-MenuGridButton.ps1) — generates a menu-grid `.res` file by splicing a name/tooltip/description into an existing button's icon, without needing the (unavailable) official resource editor.
