# FlowBalance backend

Independent FlowBalance backend repository layer.

Development API: `POST /flowbalance/calc`. It performs read-only repository access, runs `mattar-mcneil-flowing-pz-preview-v1` in memory, and returns separate runtime node 57/58 JSON objects. It never calls the original `/fmb/calc`, runs a migration, writes a result row, or creates a persistent tree node.

Runtime database access uses `mysql2/promise` through `src/db/mysqlPool.js`.
The Docker CLI helper under `scripts/test/` is only for read-only cross-checks against the current local Docker database.

Verified data mapping for `projectId=4`, `gasReservoirId=2`, `wellName=X-1`:

| Repository field | Table | Field | Unit handling | Scope |
|---|---|---|---|---|
| projectId | `project_production_data` | `project_id` | n/a | project |
| gasReservoirId | `project_production_data` | `project_gas_reservoir_id` | n/a | project gas reservoir |
| wellName | `project_production_data` | `well_name` | n/a | single well |
| production date | `project_production_data` | `date` | ordered timestamp | ordered time series |
| daily gas | `project_production_data` | `daily_gas_production` | source value | ordered time series |
| cumulative gas | `project_production_data` | `cumulative_gas_production` | source value | ordered time series |
| nodeType 57 pressure | `project_production_data` | `well_head_tubing_pressure` | `rawPressurePa` plus `pressureMpa = rawPressurePa / 1000000` | real wellhead tubing flowing pressure |
| nodeType 58 pressure | `project_production_data` | `calculated_bottom_hole_pressure` | `rawPressurePa` plus `pressureMpa = rawPressurePa / 1000000` | calculated bottom-hole flowing pressure |
| original formation pressure | `project_other_data` | `original_formation_pressure` | source Pa, validated as 50 MPa for X-1 | single well |
| formation temperature | `project_other_data` | `formation_temperature` | source value | single well |
| gas components | `project_gas_property` | `specific_gravity`, `hydrogen_sulfide`, `carbon_dioxide`, `nitrogen` | source value | single well |

Pressure node rules:

- `sourceUnit = Pa`
- `standardUnit = MPa`
- `conversion = value / 1000000`
- nodeType 57 uses `pressureSource=well_head_tubing_pressure`, `pressureKind=wellhead`.
- nodeType 58 uses `pressureSource=calculated_bottom_hole_pressure`, `pressureKind=calculated`.
- nodeType 58 is not measured and is not mixed with `measured_bottom_hole_pressure`.
- No `COALESCE` or dynamic fallback is used.
- Repository reads `calculated_bottom_hole_pressure` through its own SQL query and merges it into the production series by production row id.
- Invalid dates, missing pressure rows, non-finite pressure values, and non-positive pressure values raise domain errors.
- This phase enables real Repository data capability only. It does not create `flow_balance_node` rows, run migrations, or write business data.

## Unit Evidence

The pressure unit decision is based on combined evidence, not on empty unit tables alone:

- `grdp-server` metadata strings identify `CalculatedBottomHolePressure` as `json:"calculatedBottomHolePressure"` with the Chinese name `井底压力（计算）`, and the binary contains the database field `calculated_bottom_hole_pressure`.
- Built `grdp-web` resource `/usr/share/nginx/html/assets/ResultAnalyticMethod-41b7706a.js` maps API production data with:
  `getWelldatapreprocess(...).then(e => { e.productionData.forEach(e => { e.bottomPressure = e.calculatedBottomHolePressure }) ... })`.
- The same built resource uses `bottomPressure` as the analytic production-data field, fills the import/template table from `inputItems`, gets display units from `cData.fields.find(e => e.name == l.field).unit_label`, and rejects rows where `bottomPressure >= originalFormationPressure`.
- Local `vue/src/views/WellControlInventory/AnalyticMethodContent.vue` labels `bottomPressure` as `真实井底流压(MPa)` and compares imported `bottomPressure` with `originalFormationPressure`.
- Local `DynamicBalanceContent.vue`, `NpiContent.vue`, `WaterInvasionContent.vue`, and material-balance chart labels display pressure inputs or results in MPa.
- `grdp-server` `/app/etc/*.yaml` and `/app/etc/weaver.toml` contain database/storage/service settings but no conflicting default pressure unit or SI override.
- All database tables with names containing `unit` were checked: `base_global_unit`, `common_unit_my`, `common_unit_templates`, `project_unit_setting`, and `project_unit_setting_detail`. Their structures exist, but each has 0 rows in the inspected database.

## X-1 Physical Consistency

Read-only validation for `projectId=4`, `gasReservoirId=2`, `wellName=X-1`:

| Check | Result |
|---|---:|
| production rows | 3556 |
| original formation pressure raw | 50000000 Pa |
| original formation pressure converted | 50 MPa |
| wellhead tubing pressure raw range | 7080000 to 24060000 Pa |
| wellhead tubing pressure converted range | 7.08 to 24.06 MPa |
| calculated bottom-hole pressure raw range | 11226379.42819062 to 33689238.777410485 Pa |
| calculated bottom-hole pressure converted range | 11.22637942819062 to 33.68923877741049 MPa |
| bottom-hole pressure not above wellhead pressure | 0 rows |
| bottom-hole pressure not below original formation pressure | 0 rows |
| wellhead pressure not below original formation pressure | 0 rows |
| invalid pressure/date rows | 0 rows |
| date inversion count | 0 rows |

Sample converted rows:

| date | wellhead raw Pa | wellhead MPa | calculated bottom-hole raw Pa | calculated bottom-hole MPa |
|---|---:|---:|---:|---:|
| 2004-04-21 | 21204000 | 21.204 | 27526233.155847643 | 27.526233155847642 |
| 2004-04-22 | 20700000 | 20.7 | 31101948.625420153 | 31.101948625420153 |
| 2004-04-23 | 22602000 | 22.602 | 32632188.706460398 | 32.6321887064604 |

The range, field semantics, UI labels, built-resource mapping, and cross-field pressure relationship support storing source pressure as Pa and using MPa as the FlowBalance standard unit.

## Tests

Run from `backend/flowbalance`:

```powershell
npm run check
npm test
npm run test:integration:docker
npm run validate:docker
```

Start the development HTTP service on the existing MariaDB Docker network:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/start-dev.ps1
```

`test:integration:docker` builds `Dockerfile.integration`, attaches the test container to the current `mariadb` Docker network, uses `mariadb:3306` as the database endpoint, reads credentials from the existing container environment, and passes them only as process environment variables. It does not print passwords, create `.env` files, publish database ports, run migrations, or write business data.
