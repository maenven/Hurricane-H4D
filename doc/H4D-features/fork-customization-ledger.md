# Fork customization ledger

The canonical list of **intentional** divergence from NightDawg upstream. During a sync, anything
not listed here defaults to upstream's current version. If you add a durable customization,
add an entry here. Procedure: [upstream-merge-workflow.md](upstream-merge-workflow.md).

"Shared hooks" are edits inside files upstream also owns — the places a merge can collide.

## 1. Yapper Bot

- **Behavior:** toggle under Custom Client Extras → Bots → Yapper Bot. Sends a random line from
  `Yapper_Bot_Phrases.txt` to the selected chat tab on an interval (Options → Advanced Settings →
  Chat Settings → Yapper Bot Interval).
- **Why:** hands-free chat presence without stealing focus or interrupting play.
- **Fork-owned:** `src/haven/automated/YapperBot.java`, `res/customclient/menugrid/Bots/YapperBot.res`,
  `Yapper_Bot_Phrases.txt` (repo root).
- **Shared hooks:** `GameUI` (`yapperBot`, `yapperBotThread` fields); `MenuGrid` (`makeLocal(...Bots/YapperBot)`
  and the `useCustom` `"YapperBot"` branch); `OptWnd` (`yapperBotIntervalTextEntry`); `build.xml`
  (`bin` copies `Yapper_Bot_Phrases.txt`).
- **Invariants:** sends via `ChatUI.EntryChannel.send()` and never touches keyboard/mouse focus;
  skips cycles if no `EntryChannel` is selected; interval pref `yapperBotInterval` re-read each
  cycle, floor 0.1 s; toggling off sets stop *and interrupts* the thread; phrases re-read from
  disk on each toggle-on; phrase file ships at the root of `bin/` and stays user-editable;
  title spells "Spitsbergen" and the tooltip describes the interval setting.
- **Superseded when:** upstream ships an equivalent chat-spam/auto-say bot with a configurable
  interval, external editable phrase list and no focus stealing.
- **Detail:** [yapper-bot.md](yapper-bot.md), [chat-message-injection.md](chat-message-injection.md),
  [menugrid-system.md](menugrid-system.md).

## 2. H4D release architecture

- **Behavior:** releases are built fresh from source by the manual `Create Release` GitHub Action
  (`ant bin` → zip of `bin/` contents → `gh release create`). The old root `Release/` snapshot is
  **intentionally deleted**.
- **Why:** the committed `Release/` was hand-maintained and silently went stale (a downloaded
  release lacked the Yapper Bot entirely).
- **Fork-owned:** `.github/workflows/release.yml`.
- **Shared hooks:** `build.xml` (`Yapper_Bot_Phrases.txt` copy in `bin`).
- **Invariants:** root `Release/**` stays absent. Upstream modifying `Release/**` (jar, manifest,
  res) is *not* a reason to restore it — resolve modify/delete conflicts by keeping the deletion;
  upstream's source/build changes are still taken in full. `ant bin` is the canonical
  distributable. Do **not** assume the current workflow ships `hafen-panama.jar`; it does not
  (see the backlog).
- **Superseded when:** n/a — this is fork policy, not an upstream feature.
- **Detail:** [release-process.md](release-process.md).

## 3. 40-group permission extension

- **Behavior:** Kin, Village and Field Cairn permission groups assignable 0–39 (not 8) through a
  companion dropdown with editable per-group labels; `[N]` tags in Kin/Village lists. Personal
  claim stays 0–7.
- **Why:** more distinct permission groups than vanilla's 8 squares.
- **Fork-owned:** `src/haven/groups/` (`GroupSelectorClassifier`, `GroupSelectorCompanion`,
  `GroupLabelPopup`, `GroupLabels`).
- **Shared hooks:** `BuddyWnd` (`nquick`, `ncolors`, `gc[]`, `GroupSelector.update/attached/dispose`,
  `Buddy.grouptag()`); `Polity` (`Member.group`, extraction in `uimsg("add")`, `MemberList` draw
  guard); `MapWnd` and `ProspectingWnd` (random marker color); `res/gfx/hud/mmap/plo/Factory.java`;
  `GameUI.error` warning use.
- **Invariants (essentials):** `BuddyWnd.nquick = 8` (physical squares), `ncolors = 40`, `gc[]`
  safety-sized to **255** because server resource code indexes it directly; 0–7 palette byte-identical
  to original; `GroupSelector.update()` bounds-checks the upper end; `Polity.Member.group` is set in
  `Polity.uimsg("add")` **after** `parsememb()` returns (Village overrides `parsememb`); the
  `Polity.MemberList` `try/catch(Throwable)` draw guard is permanent; map/prospecting random colors and
  the minimap player-icon enumeration are bounded by `nquick`; warning when Village/Field Cairn
  group `>= 8`; `group == -1` displays as 0 without writing back; Kin/claim/Cairn labels scoped by
  `GameUI.chrid`, Village labels by `Polity.name`, stored as pref JSON. Do not "correct" the
  server-resource class name `haven.ers.ui.vmemb.VillageMember` ("ers" is real).
- **Superseded when:** upstream ships a >8-group picker covering Kin/Village/Field Cairn that also
  makes `BuddyWnd.gc[]` safe for direct resource indexing.
- **Detail (authoritative):** [group-permission-extension.md](group-permission-extension.md).

## 4. Skis reliability and speed-4 restore

- **Behavior:** the Skis script mounts skis reliably under latency, and the client automatically
  restores speed 4 after stamina forces skis from speed 4 to 3 and stamina recovers.
- **Why:** mounting used blind fixed delays and failed at higher latency; stamina drops left the
  player stuck at the slower speed.
- **Fork-owned:** `src/haven/automated/SkisScript.java`.
- **Shared hooks:** `GameUI` (`skiSpeedTick()` state fields and its per-frame call); `MenuGrid`
  (`SkisScript` registration/branch).
- **Invariants:** mount polls for dropped skis and waits for `player.imOnSkis` acknowledgement,
  retrying only when needed; the restore state lives in `GameUI` so it works however the skis were
  mounted; it remembers only stamina-forced fallbacks (speed 4 no longer allowed by `Speedget.max`),
  assists stamina recovery, and re-engages speed 4 once allowed; a **deliberate** slowdown while speed 4
  is still allowed is respected and never restored.
- **Superseded when:** upstream implements equivalent ack-based mounting and stamina-aware
  speed restoration.
- **Detail:** none yet beyond code comments in `GameUI.skiSpeedTick()` and `SkisScript`.

## 5. Auto Kin (Web Map group kinning)

- **Behavior:** with "Share my hearth secret with my Web Map group" on (Options → Advanced
  Settings → Server Integration), the client publishes the character's hearth secret to the Web Map
  server, generates one if the character has none, and ~15 s after login replays every other group
  member's secret as "Add kin by Hearth Secret". "Sync kin from Web Map" in Kith & Kin does it on
  demand (shift-click resends to current kin too).
- **Why:** everyone on the group's mapper server gets kinned automatically, without trading secrets.
- **Fork-owned:** `src/haven/automated/AutoKin.java`.
- **Shared hooks:** `BuddyWnd` (`autoKin` field, `autoKinStatus` label + sync button after
  "Add kin from file", `tick()` override, one call each in `setpwd()`, `uimsg("add")`,
  `uimsg("pwd")`); `OptWnd` (four `autoKin*CheckBox` fields + tooltips in
  `ServerIntegrationSettingsPanel`, `AutoKin` import).
- **Invariants:** reuses the `webMapEndpoint` pref (`https://host/client/<token>`), no separate
  token; endpoints `POST /kinSecret` and `GET /kinSecrets?genus=`; nothing is sent unless
  `autoKinShareSecret` is on (default **off**); generation never replaces an existing secret and a
  manual Clear holds for the session; secrets are never logged; HTTP stays off the UI thread.
- **Superseded when:** upstream ships an equivalent shared-secret auto-kin.
- **Detail:** [auto-kin.md](auto-kin.md).

## 6. Cart/wagon load and unload from an area

- **Behavior:** with "Load/Unload Carts & Wagons from an Area (Ctrl+Right-Click)" on (Options →
  Advanced Settings → Gameplay Automation Settings, default **on**), Ctrl+Right-Click on a cart or wagon with
  nothing lifted opens a small menu: *Load … from area* (drag a rectangle, pick the kind of object if
  there are several, lift them in one by one) or *Unload … into area* (drag a rectangle, set the
  cargo down in free spots). Ctrl+Right-Click the vehicle again while it runs to stop it.
- **Why:** ported from the Nurgling2 client's Load/Unload vehicle context actions.
- **Fork-owned:** `src/haven/automated/VehicleAreaTransfer.java`,
  `src/haven/automated/helpers/ClientMenu.java`.
- **Shared hooks:** `MapView` (`Click.hit` Ctrl+RMB hook before the right-click branch;
  `Selector.clientCallback` + its `mmouseup`/right-click-cancel branches; `selectAreaClientSide`,
  `cancelClientAreaSelect`, `isPlacingReady`); `GameUI` (`vehicleAreaTransferThread`); `OptWnd`
  (`vehicleAreaTransferCheckBox` + tooltip before "Walk with Pathfinder").
- **Invariants:** a client-started selection never sends `"sel"` to the server; Ctrl+RMB while
  carrying an object or holding an item, or with the option off, falls through to the normal click;
  objects already in the unload area are never moved; a failed store puts the object back where it was.
- **Superseded when:** upstream ships an equivalent area load/unload for vehicles.
- **Detail:** [vehicle-area-transfer.md](vehicle-area-transfer.md).

## 7. Craft a chosen quantity

- **Behavior:** the crafting window gets an amount box and a **Craft #** button; it crafts that
  many one after another and the button turns into `Stop done/total` while running.
- **Why:** Ender's and Kami's clients have it; Ian asked for it (2026-11-03).
- **Fork-owned:** none (all in `Makewindow`).
- **Shared hooks:** `Makewindow` constructor (button row re-laid out: amount box, Craft #, Craft,
  Craft All; Craft/Craft All also stop a batch), the H4D craft-quantity block (`CountEntry`,
  batch state, `tick()` override).
- **Invariants:** only ever sends the stock `make 0`; a plain Enter still crafts one; a batch
  stops when no progress bar starts within 2 s.
- **Superseded when:** upstream ships a craft-amount control.
- **Detail:** [craft-quantity.md](craft-quantity.md).

## 8. Light fires in an area with a candelabrum or torchpost

- **Behavior:** menu-grid button *Light Fires in Area* (Custom Client Extras → Other Scripts &
  Tools). Press it, drag a rectangle over a lit candelabrum or a torchpost holding a lit torch, then
  drag one over the workstations; every unlit kiln, smoke shed, oven, fireplace (and the other fire
  workstations listed in the detail file) in it gets lit, then the source goes back where it was.
  Press the button again to stop. (A first version used Ctrl+Right-Click on the source; Ian found the
  click reached the server and put the candelabrum out, so it moved to a button.)
- **Why:** ported from the Nurgling2 client's unified lighter (`LightObject`).
- **Fork-owned:** `src/haven/automated/AreaFireLighter.java`,
  `res/customclient/menugrid/OtherScriptsAndTools/LightFiresInArea.res`.
- **Shared hooks:** `MenuGrid` (one `makeLocal` line, one `useCustom` branch); reuses
  `MapView.selectAreaClientSide` / `isPlacingReady` from §6.
- **Invariants:** the candelabrum/torch is returned even when the run is stopped.
- **Detail:** [area-fire-lighter.md](area-fire-lighter.md).

## 9. Block stone actions while carrying Rock Crystal

- **Behavior:** option *Block Stone Actions while carrying Rock Crystal* (Options → Gameplay
  Automation, off by default). While on and a Rock Crystal is in the main inventory, crafting a
  recipe with a stone input, pressing **Build** on a construction site and starting the
  **Landscape** tool are refused client-side with "Action denied, please remove Rock Crystal to
  continue your actions."
- **Why:** Ian asked for it (2026-11-03) so a crystal is never used up as stone by accident.
- **Fork-owned:** `src/haven/RockCrystalGuard.java`.
- **Shared hooks:** one line at the top of `UI.wdgmsg(Widget, String, Object...)`; `OptWnd`
  checkbox + tooltip; `Makewindow.stopBatch` widened from private to package-private.
- **Invariants:** with the option off (or no crystal) every message passes untouched.
- **Detail:** [rock-crystal-guard.md](rock-crystal-guard.md).

## 10. Shear sheep and goats in an area

- **Behavior:** menu-grid button *Shear Animals in Area* (Custom Client Extras → Other Scripts &
  Tools). Press it, drag a rectangle over the animals; the best shears carried go into a hand (the
  highest quality axe when there are no shears), then every domestic sheep and goat in the area is
  right-clicked → *Shear wool*, nearest first. Press the button again to stop.
- **Why:** Ian asked for it (2026-11-03); shearing follows Nurgling2's `ShearWool`.
- **Fork-owned:** `src/haven/automated/AreaShearer.java`,
  `res/customclient/menugrid/OtherScriptsAndTools/ShearAnimalsInArea.res`.
- **Shared hooks:** `MenuGrid` (one `makeLocal` line, one `useCustom` branch); reuses
  `MapView.selectAreaClientSide` from §6.
- **Detail:** [area-shearer.md](area-shearer.md).

## 11. Milk animals in an area into barrels

- **Behavior:** menu-grid button *Milk Animals in Area* (Custom Client Extras → Other Scripts &
  Tools, milk-bucket icon). Press it, drag a rectangle over the cows/goats/sheep, then one over the
  empty barrels. The character lifts the nearest empty barrel, Shift+Right-Clicks each animal with
  it, sets the barrel back where it stood when the server says it's full, and takes the next empty
  barrel from the same area. One species per barrel. Press the button again to stop.
- **Why:** Ian's request; mechanics from Nurgling2's `MilkAnimalsAction`.
- **Fork-owned:** `src/haven/automated/AreaMilker.java`,
  `res/customclient/menugrid/OtherScriptsAndTools/MilkAnimalsInArea.res`.
- **Shared hooks:** `MenuGrid` (one `makeLocal` line, one `useCustom` branch); `UI` gained
  `lastServerError/Info/Sfx` + counters, fed by one-line `// H4D` calls in `GameUI.uimsg` ("err",
  "msg") and `RootWidget.uimsg` ("err", "msg", "sfx") — re-add them if an upstream merge rewrites
  those branches.
- **Invariants:** the carried barrel is set down even when the run is stopped.
- **Detail:** [area-milker.md](area-milker.md).

## 12. Village member list: sort and filter by group

- **Behavior:** a control row at the top of the village member list: a *Sort: Name / Sort: Group*
  toggle (remembered across sessions, pref `polityMemberSortByGroup`) and a group dropdown
  (*All groups*, then each group that has members, with its color, label and member count). Typing
  `#3` in the list searches group 3 (`#3 bob` = group 3 and name contains "bob").
- **Why:** Ian's request.
- **Shared hooks:** `Polity.MemberList` (sort/filter list, `installControls()` takes one row off the
  top of the list so the server resource's layout never moves) and `Polity.MemberListControls`;
  `SSearchBox.refilter()` (re-runs the search without selecting a member).
- **Detail:** [village-member-sort.md](village-member-sort.md).

## 13. Auto re-aggro animals that leave combat (Ender/Kami style)

- **Behavior:** checkbox *Auto Re-aggro Animals that leave Combat* in Options → Gameplay/combat
  settings, right under *Auto-Peace Animals when Combat Starts* (pref `autoReaggroAnimals`, off by
  default). When any combat relation is removed (animal fled, or ran off after being peaced), the
  client starts combat with it again if it is a `gfx/kritter/` gob, not knocked out, still loaded
  and within 200 world units (~18 tiles) — the same rules as `Actions.reAggroKritter` in Ender's
  and Kami's clients (`ui.combat.reaggro`). Applies to every relation, not only the current target;
  never to players.
- **Why:** Ian's request — Hurricane's own re-aggro is a per-target toggle that only fires for the
  current target.
- **Shared hooks:** `Fightview.uimsg("del")` (calls `reAggroAnimal`, skipped when the upstream
  per-target `AutoGiveButton` path already re-aggroed the same gob) and `OptWnd` (checkbox +
  tooltip). The upstream per-target toggle and its hotkey are unchanged.

## 14. Barrel: take a chosen amount

- **Behavior:** barrel windows get an amount box and a **Take #** button; it presses the stock Take
  once per unit until that many units of flour/salt/cave dust/seeds arrived, switching Automatic
  Item Stacking off for the run and back on afterwards. Option *Barrel Window: Take a chosen
  Amount* in Interface settings (pref `barrelTakeAmount`, on by default).
- **Why:** Ian's request (2026-11-03): one Take hands out a full stack.
- **Fork-owned:** `BarrelTakeAmount`.
- **Shared hooks:** `Window.tick` → `barrelTakeTick()` (adds/positions the row in windows captioned
  `Barrel`), `OptWnd` (checkbox + tooltip after *Stack Windows when Opened*).
- **Invariants:** only ever clicks the server's own Take button; never leaves stacking switched
  off (restored on finish, stop and window close).
- **Superseded when:** upstream ships a barrel take-amount control.
- **Detail:** [barrel-take-amount.md](barrel-take-amount.md).

## 15. Fill Barrels from Vehicle

- **Behavior:** menu-grid button *Fill Barrels from Vehicle* (Other Scripts & Tools): click a source
  (well, cistern, milling machine…), then a cart/wagon/snekkja/knarr; every empty barrel in the
  vehicle is taken out, filled at the source (Shift+Right-Click while carried) and put back.
- **Why:** Ian's request (2026-11-04), port of Nurgling2's "Fill barrels from well".
- **Fork-owned:** `automated/VehicleBarrelFiller`, `FillBarrelsFromVehicle.res`.
- **Shared hooks:** `MapView.Click.hit` → `clientGobPickHit` (one-shot client-side "click an
  object", `startClientGobPick`), `MenuGrid` (makeLocal + useCustom branch).
- **Detail:** [vehicle-barrel-filler.md](vehicle-barrel-filler.md).

## 16. Memorized animal stats above heads

- **Behavior:** animals in the Cattle Roster show `Q | B | M` (quality, breeding quality, milk %)
  above their name even with the roster closed; optionally the animal(s) with the best value of one
  chosen stat among their species' roster get green text and a green gob tint. Toggle button
  *Custom Client Extras → Toggles → Animal Stats Above Heads* and a checkbox + "highlight best"
  dropdown on the Cattle Roster button row (prefs `animalStatsOverhead`, `animalStatsHighlight`).
- **Why:** Ian's request (2026-11-04): stop reopening the roster to compare animals.
- **Fork-owned:** `automated/AnimalStatsOverlay`, `res/customclient/menugrid/Toggles/AnimalStatsOverhead.res`.
- **Shared hooks:** `croster/CattleId.draw` (stats line, name shown while enabled), `CattleId.roster()`,
  `croster/RosterWindow.addroster` (controls), `GameUI.tick` (best-animal tint), `MenuGrid` (toggle).
- **Detail:** [animal-stats-overhead.md](animal-stats-overhead.md).
