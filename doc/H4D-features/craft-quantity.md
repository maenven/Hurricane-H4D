# Craft quantity (Craft #)

The crafting window (`Makewindow`) has an amount box and a **Craft #** button between the inputs and
the stock **Craft** / **Craft All** buttons. Type a number and press **Craft #** (or Enter inside the
box) to craft exactly that many. While it runs the button reads `Stop done/total`; click it to stop.

## How it works

The server only understands `wdgmsg("make", 0)` (make one) and `wdgmsg("make", 1)` (make all), so
the client loops: send `make 0`, wait for `GameUI.prog` (the progress bar) to appear, wait for it to
go away, count one, pause 0.25 s, repeat. Same approach as Ender's client
(`auto/Actions.craftCount` + `BotUtil.waitProgress`). It is a tick-driven state machine inside
`Makewindow` (no thread), so it dies with the widget: switching recipe or closing the window ends
the batch.

- No progress bar within 2 s after a `make 0` = the craft could not start (out of materials,
  stamina, inventory space…): the batch stops with a system message saying how many were made.
- Clicking **Craft** or **Craft All** (or their hotkeys) stops a running batch first.
- The last amount is remembered in the pref `makewnd-craftcount` (min 1).
- The box never takes focus by itself (`autofocus = false`), so a plain Enter still means Craft
  one; only Enter typed inside the box starts a batch. Digits only, max 4.

## Known limits

- Walking away mid-craft cancels the server's progress; the client counts that as done and starts
  the next one. Use Stop.
- A recipe without a progress bar (instant craft) stops after one.
