# Village member list: sort and filter by group

The village window (`haven.res.ui.vlg.Village`) is a server resource, but its member list is the
client's own `Polity.MemberList`, so all of this lives in `src/haven/Polity.java`.

- `MemberList.mlist` stays the full, sorted member list (public, in case resource code reads it).
  `shown` is `mlist` narrowed to `groupFilter`, and is what `allitems()` returns, so the name
  search runs inside the group filter.
- Sort by group: ascending group, unassigned (`-1`) last, then name. Sort by name is the vanilla order.
- Group comes from `Member.group`, set in `Polity.uimsg("add")` (see
  [group-permission-extension.md](group-permission-extension.md) for why it is set there).
- **Placing the controls:** there is no client-side layout for the village window, so on its first
  tick the list adds a `MemberListControls` row to its parent at its own position, moves itself down
  one row and shrinks by one row. Total footprint is unchanged, so nothing in the resource's layout
  is covered. The controls are destroyed with the list.
- `SSearchBox.search()` selects the first match (which sends `sel` to the server). After the list
  changes, `refilter()` re-runs the current search without touching the selection.
- Dropdown labels use the village group labels (`GroupLabels.Scope.VILLAGE`, keyed by `Polity.name`).
