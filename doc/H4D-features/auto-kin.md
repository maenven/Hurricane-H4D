# Auto Kin

Players on the same Web Map (HnHMapperServer) tenant kin each other automatically. Each client
publishes its character's hearth secret to the mapper, and every other client replays it through
the normal "Add kin by Hearth Secret" message (`bypwd`). `bypwd` creates kinship immediately; the
other player does not have to accept anything.

Client code: `src/haven/automated/AutoKin.java`, driven from `BuddyWnd` (one instance per Kith & Kin
window). Ported from Nurgling2's `NBuddyWnd` kin-secret sharing (detoxboss/nurgling2-imbecil), which
uses its own database instead of the mapper.

## Server contract

Both endpoints sit under the existing client token URL, i.e. the "Web Map Endpoint" setting
(`https://host/client/<token>`, pref `webMapEndpoint`). The server must add these; stock
HnHMapperServer returns 404, which the client reports as "Web Map server has no Auto Kin".

| Call | Body / query | Response |
| --- | --- | --- |
| `POST /kinSecret` | `{"genus": "...", "charName": "...", "secret": "..."}`; empty `secret` deletes the row | 200/204 |
| `GET /kinSecrets` | `?genus=<world genus>` | 200, `[{"charName": "...", "secret": "...", "updatedAt": "..."}]` |

- Rows are keyed by (tenant, genus, charName). `genus` is `GameUI.genus`, `charName` is `GameUI.chrid`.
- **Only tenant admins** (`TenantRole.TenantAdmin`) may publish or pull; anyone else gets 403, shown
  as "Auto Kin needs Web Map admin". A 403/404 on publish stops retries for the session.
- The server must never show the secret column in its web UI or database viewer.

## Client flow

1. `uimsg("pwd")` tells AutoKin the character's current secret; `setpwd()` reports manual changes.
   Each new value is published (retry every 30 s on network errors).
2. If no secret is known 10 s after the window is created (or the server stated an empty one), and
   sharing + "Generate a hearth secret" are on, a random 8-character `SecureRandom` secret is set.
   A manual Clear disables generation for the rest of the session.
3. 15 s after login (option "Auto-kin my Web Map group on login"), or on the "Sync kin from Web Map"
   button, it GETs the list, skips its own character and anyone already in the kin list **by name**,
   and sends one `bypwd` every 0.25 s.
4. `bypwd` is never acknowledged, so any `uimsg("add")` within 10 s of a send counts as success and,
   with "Put kin added by Auto Kin in the green group" on, is moved to group 1.

Name matching means kin shown under a nickname get their secret re-sent each sync; harmless, the
game ignores `bypwd` for existing kin.

## Prefs

`autoKinShareSecret` (default off, master switch), `autoKinGenerateSecret` (on), `autoKinOnLogin`
(on), `autoKinGreenGroup` (on).
