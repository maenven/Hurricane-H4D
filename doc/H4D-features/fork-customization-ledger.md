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
