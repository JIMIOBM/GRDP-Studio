# Repository collaboration constraints

## Change scope and data safety

- Keep every change within the scope explicitly requested by the user.
- Do not change database IDs, seeded data, test data, or persisted-record identifiers unless the user explicitly requests it.
- Do not change another contributor's styling, interaction design, or unrelated code while implementing a focused fix.
- Do not include opportunistic refactors, formatting sweeps, generated artifacts, or unrelated dependency updates in a focused change.

## Integration and remote-main safety

- Re-read the latest remote `main` before integrating or publishing work.
- When upstream changed the same file, perform a three-way merge from the known common base. Preserve upstream behavior and combine both sides intentionally; never replace the remote file wholesale with a stale local copy.
- Before updating `main`, verify that the final diff contains only the intended paths and run validation proportional to the change.
- Update `main` only by a non-forced fast-forward operation. Check the remote pointer immediately before and after the update, then verify every published file by its Git blob hash.
- Never print credentials or write credentials to disk. Credentials obtained from the system credential manager must remain in process memory only.

## Productivity-test regression invariants

- Reuse the standard `回压试井` and `一点法` method directories; do not recreate duplicate `回压` or `一点` directory nodes.
- Standard productivity-test method node types must remain selectable and must resolve to their corresponding page method.
- Switching between production and injection must clear the previous calculation result and prevent stale results from being displayed or saved.
- A one-point exponential regression curve must retain its full calculated range and must not be clipped to the single measured flow-rate coordinate.
- Preserve isochronal persistence behavior, including reloading with `getIsochronal` after save, restoring through `restorePersisted`, and selecting the persisted result that matches the active pressure method and calculation-result type.
