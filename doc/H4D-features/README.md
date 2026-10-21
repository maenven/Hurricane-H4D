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

Tools: [tools/New-MenuGridButton.ps1](tools/New-MenuGridButton.ps1) — generates a menu-grid `.res` file by splicing a name/tooltip/description into an existing button's icon, without needing the (unavailable) official resource editor.
