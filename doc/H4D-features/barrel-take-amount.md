# Barrel: take a chosen amount (Take #)

Barrel windows get an amount box and a **Take #** button under the contents. Type a number and press
**Take #** (or Enter inside the box) to take exactly that many units of flour, salt, cave dust,
seeds... While it runs the button reads `Stop got/total`; click it to stop. Option:
*Interface settings → Barrel Window: Take a chosen Amount* (pref `barrelTakeAmount`, on by default;
reopen the barrel after changing it). The last amount is kept in pref `barrel-takecount`.

## How it works (`BarrelTakeAmount`, hooked from `Window.barrelTakeTick`)

- The barrel window (caption `Barrel`) is built by the server: a remote `ui/relcnt` `RelCont`
  holding a contents label and the stock **Take** button. The client finds that button by its
  text anywhere in the window and adds its own row under the lowest other child, repositioning
  (and re-packing the window) whenever the server's contents move.
- The server only knows the stock Take, so the client presses it once per unit (`Button.click()`),
  waits up to 2 s for the unit count to rise (main inventory + cursor; a stack counts as the items
  in it), pauses 0.1 s, repeats. Same idea as Nurgling2's `TakeFromBarrel(barrel, items, count)`.
  Ender has no barrel equivalent (only the stockpile `ISBox` take box, which this fork already has
  for Stockpile/Sack windows).
- **Automatic Item Stacking:** with stacking on, one Take hands out a whole stack. If the stacking
  toggle buff (`itemcomb`) is up, the run sends `act itemcomb` first (waits ≤2 s for the buff to
  go), and sends it again when done, when stopped, or when the window closes (`dispose`). The
  "Stacking is now off/on." chat lines are expected.
- Stops when the Take button disappears (barrel empty) or no unit arrives within 2 s (empty, no
  room). If one press ever yields more than one unit it reports the overshoot instead of hiding it.

## Known limits

- Units taken while stacking was off stay as loose items after stacking is switched back on.
- Anything else entering the inventory during a run (picking things up) is counted as taken.
- Untested assumption: with stacking off, one Take = one unit. If that is wrong the run stops after
  the first press and the message says how many came out.
