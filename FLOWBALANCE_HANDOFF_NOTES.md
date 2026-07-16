# FlowBalance handoff notes

## Delivered scope

- Independent `POST /flowbalance/calc` development endpoint.
- Existing `mysql2/promise` Repository real-data input.
- Pure in-memory `mattar-mcneil-flowing-pz-preview-v1` calculation.
- Separate runtime node 57 and 58 JSON results.
- Runtime UUIDs explicitly marked `persisted=false`, `idKind=runtime`.
- FlowBalance-only Vue API, trigger, runtime tree nodes and result/chart page.
- Vite preview port `5174` and `/flowbalance` proxy.
- Docker development start/stop scripts and host validation instructions.

No migration or database write is invoked. The project does not include FlowBalance migration files. No reading or writing to FlowBalance result tables. Only real-time reads from project_* tables with in-memory calculation. No original `/projectanalysis/dynamicoriginalgasInplace/fmb/calc` call remains in the FlowBalance trigger.

## Validation completed in the handoff environment

- `npm run check`: passed.
- `npm test`: passed, 9 tests.
- HTTP health handler: passed with `200` and `persistence=false`.
- `FlowBalanceContent.vue` and `IprInterface.vue`: Vue SFC script/template compilation passed.
- `docker.js` and `vite.config.js`: syntax check passed.
- Full Vue build could not run because the uploaded handoff intentionally contains only selected Vue files and no `index.html` or complete application tree.
- Real X-1 Docker integration cannot run outside the user's GRDP Docker network. Run the exact TRAE instruction in `backend/flowbalance/docs/FLOWBALANCE_DEV_PREVIEW.md`.

## Apply to the Windows repository

Extract this archive over:

```text
D:\Project\GRDP-Studio-flow-balance
```

Preserve any unrelated local changes. Then run the checks in `backend/flowbalance/docs/FLOWBALANCE_DEV_PREVIEW.md`. Do not copy `node_modules` (none is included in the delivery archive).