# FlowBalance Algorithm Contract

Date: 2026-07-14

Scope: determine the real Flowing Material Balance (FMB) data/result contract and decide whether the in-memory calculation layer can be implemented without calling or copying the original `grdp-server` FMB service.

## Guardrails Applied

- Did not call `/projectanalysis/dynamicoriginalgasInplace/fmb/calc`.
- Did not copy, decompile, or reconstruct original service implementation.
- Did not write database rows, run migrations, or create `flow_balance_result` / `flow_balance_node`.
- Did not modify Vue, water-invasion, or material-balance modules.
- Did not commit or push.

## Repository Baseline

Commands run from `backend/flowbalance`:

| Command | Result |
|---|---|
| `npm run check` | pass |
| `npm test` | pass, 2 repository tests |
| `npm run test:integration:docker` | pass after Docker API escalation, 1 mysql2 integration test |

Repository mapping remains unchanged:

| Node type | Pressure field | pressureKind | Source unit | Standard unit |
|---:|---|---|---|---|
| 57 | `project_production_data.well_head_tubing_pressure` | `wellhead` | Pa | MPa |
| 58 | `project_production_data.calculated_bottom_hole_pressure` | `calculated` | Pa | MPa |

Node type 57 and 58 must be calculated independently from their own pressure series. One result must not be copied into the other node type.

## Database Contract

Read-only table discovery found these FMB/dynamic-original-gas-in-place tables:

- `dynamic_original_gas_in_place`
- `dynamic_original_gas_in_place_by_fmb_input`
- `dynamic_original_gas_in_place_by_fmb_input_item`
- `dynamic_original_gas_in_place_by_dmb_input`
- `dynamic_original_gas_in_place_by_dmb_input_item`
- `dynamic_original_gas_in_place_by_mb_input`
- `dynamic_original_gas_in_place_by_mb_input_item`
- `dynamic_original_gas_in_place_output`
- `dynamic_original_gas_in_place_output_item`
- `dynamic_original_gas_in_place_formation_pressure`
- `dynamic_original_gas_in_place_well_formation_pressure`
- `algorithm_configuration`
- `common_algorithm_result`

Historical row counts in the inspected database:

| Table | Row count |
|---|---:|
| `dynamic_original_gas_in_place` | 0 |
| `dynamic_original_gas_in_place_by_fmb_input` | 0 |
| `dynamic_original_gas_in_place_by_fmb_input_item` | 0 |
| `dynamic_original_gas_in_place_output` | 0 |
| `dynamic_original_gas_in_place_output_item` | 0 |
| `algorithm_configuration` | 1 |

`algorithm_configuration` sample:

| id | project_gas_reservoir_id | core_gas_reservoir_id | modification_method | deviation_factor_method | viscosity_method | material_balance_equation_type | pipe_flow_type |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | NULL | 1 | 0 | 0 | 0 | 1 | 0 |

No historical FMB input/output/item data exists in this database, so it cannot calibrate or verify OGIP, slope, intercept, R/R2, pseudo-time, or transformed pressure values.

### FMB Input Tables

`dynamic_original_gas_in_place_by_fmb_input` contains:

- gas properties: `gas_type`, `specific_gravity`, `hydrogen_sulfide`, `carbon_dioxide`, `nitrogen`
- methods: `modification_method`, `deviation_factor_method`, `viscosity_method`
- reservoir state: `original_pressure`, `temperature`
- optional shale/adsorption fields: `water_saturation`, `langmuir_pressure`, `langmuir_volume`, `reservoir_porosity`, `shale_rock_desity`
- FMB controls: `unstable_flow_period_length`, `minimum_water_gas_ratio`, `pressure_position`
- parent: `dynamic_original_gas_in_place_id`

`dynamic_original_gas_in_place_by_fmb_input_item` contains:

- `index`, `date`
- `top_pressure`, `bottom_pressure`, `top_temperature`
- `cumulative_gas_production`, `equivalent_cumulative_gas_production`
- `cumulative_water_production`, `cumulative_oil_production`
- `gas_production_time`
- `is_deleted`
- parent: `dynamic_original_gas_inplace_by_fmb_input_id`

### Output Tables

`dynamic_original_gas_in_place_output` contains:

- identity/scope: `project_id`, `project_gas_reservoir_id`, `well_name`
- method: `dynamic_original_gas_inplace_method`, `dynamic_original_gas_inplace_method_description`
- result: `original_gas_volume`, `gfi`, `gai`, `gradient`, `intercept`, `rsquared`
- reliability: `reliablity`, `reliability_desc`
- parent: `dynamic_original_gas_in_place_id`

`dynamic_original_gas_in_place_output_item` contains:

- `index`, `date`
- `formation_pressure`, `pressure`
- `daily_gas_production`, `cumulative_gas_production`, `cumulative_water_production`
- `pseudo_time`
- `linear_regression_pressure`, `shift_linear_regression_pressure`
- `ca`, `cc`
- `modified_deviation_factor`
- `is_deleted`
- parent: `dynamic_original_gas_inplace_output_id`

## Frontend Result Contract

`FlowBalanceContent.vue` delegates directly to `DynamicBalanceContent.vue`.

`DynamicBalanceContent.vue` currently requests:

```text
GET /projectanalysis/dynamicoriginalgasInplace/result/{projectId}/{gasReservoirId}/{resultId}
```

It derives `resultId` from, in order:

- `node.raw.DynamicOriginalGasInPlaceId`
- `node.raw.dynamicOriginalGasInPlaceId`
- `node.raw.dynamicOriginalGasInplaceId`
- `node.raw.resultId`
- `node.resultId`
- `node.raw.nodeId`
- `node.id`
- hard-coded well fallback: `X-1 -> 51`, `X-2 -> 52`, `X-3 -> 53`, `X-4 -> 55`, `X-5 -> 56`

This fallback is a frontend technical debt item and must be removed in a later frontend phase. This phase does not modify Vue.

### JSON Fields Read By Current Chart

Top-level:

- `input`: parameter panel source.
- `result`: output panel and regression source.
- `data`: chart point source.

`input` fields read:

- `gasType`
- `specificGravity` or `relativeDensity`
- `hydrogenSulfide` or `h2sContent`
- `carbonDioxide` or `co2Content`
- `nitrogen` or `nitrogenContent`
- `nonHydrocarbonCorrectionMethod`
- `zFactorCalculationMethod`
- `viscosityCalculationMethod`
- `originalFormationPressure` or `initialReservoirPressure`
- `formationTemperature` or `reservoirTemperature`
- `equationType` or `mbEquationType`
- `unstableFlowTime`
- `samplingPoints`
- `waterGasRatioLimit`
- `reservoirOriginalGasVolume` or `gasReservoirVolume`
- `waterSaturation` or `connateWaterSaturation`
- `rockCompressionCoefficient` or `rockCompressibility`
- `waterCompressionCoefficient` or `waterCompressibility`

`result` fields read:

- `originalGasVolume`
- `intercept`
- `gradient` or `slope`
- `rsquared`
- `reliability`

Current Vue fallback values for missing `gradient`, `intercept`, and `rsquared` are hard-coded and must not be relied on by the backend. The backend mapper must return real values or fail.

`data` item fields read:

- `pseudotime`
- `pressure`
- `isDeleted`

The chart uses:

- used points: `data.filter(item => item.isDeleted === false)`, mapped to `[Number(item.pseudotime), Number(item.pressure)]`, then filtered to positive x/y.
- background points: all `data` items mapped to the same x/y.
- x-axis label: `tca(d)`.
- y-axis label: `(Pi - Pwf)/qsc(MPa/(10^4m3/d))`.

The current route and `resultId`/`nodeId` relation are inherited from dynamic original gas in place. A future frontend phase should request node-type-specific FlowBalance results instead of using the dynamic result endpoint and hard-coded result ids.

## Vue FMB Trigger Contract

`IprInterface.vue` calls:

```text
POST /projectanalysis/dynamicoriginalgasInplace/fmb/calc
```

with payload:

```json
{
  "wellNames": ["X-1"],
  "gasReservoirType": 1,
  "gasReservoirId": 2,
  "projectId": 4,
  "waterGasRatioLimit": 0.0602
}
```

This endpoint is forbidden for this phase and was not called.

The same component recognizes node types:

- 57: flowing balance based on top/wellhead flowing pressure.
- 58: flowing balance based on bottom-hole flowing pressure.

## grdp-server Metadata Strings

Read-only `strings /app/grdp` checks on the running `grdp-server` container found metadata strings including:

- FMB routing/logic names: `DynamicOriginalGasInplaceByFMB`, `dynamicOriginalGasInplaceByFMBCalcMetrics`, `SaveFMBAlgorithmResult`
- route/build strings: `/build/internal/handler/projectanalysis/dynamicoriginalgasInplace/dynamicoriginalgasinplacebyfmbhandler.go`, `/build/internal/logic/projectanalysis/dynamicoriginalgasInplace/dynamicoriginalgasinplacebyfmblogic.go`
- data fields: `FMBPressures`, `PseudoTime`, `Pseudotime`, `OriginalGasVolume`, `Gradient`, `Intercept`, `RSquared`
- PVT/method fields: `DeviationFactor`, `ModifiedDeviationFactor`, `DeviationFactorMethod`, `ViscosityMethod`
- table/entity strings: `dynamic_original_gas_in_place_by_fmb_input`, `dynamic_original_gas_in_place_by_fmb_input_item`, `dynamic_original_gas_in_place_output`, `dynamic_original_gas_in_place_output_item`

These strings confirm field and persistence contracts only. They do not provide equations, regression-window rules, PVT correlations, or OGIP solving rules.

## Literature Evidence

Primary targets requested:

- Mattar, L. and McNeil, R., "The Flowing Gas Material Balance", 1998, OnePetro DOI page: https://onepetro.org/JCPT/article/doi/10.2118/98-02-06/32224/The-Flowing-Gas-Material-Balance
- "Using the Flowing Material Balance Model to Determine", 2022, OnePetro page: https://onepetro.org/REE/article/25/04/719/485172/Using-the-Flowing-Material-Balance-Model-to

Formula evidence used for the development preview:

- Both OnePetro pages returned HTTP `403 Forbidden`, so they are retained as bibliographic targets rather than quoted formula sources.
- The open Politecnico di Torino thesis *Rate Transient Analysis and Flowing Material Balance for Oil & Gas Reservoirs* (Mohamed Amr Aly, 2018), pages 10–11, documents the original dry-gas Mattar–McNeil construction: regress flowing `P/Z` against cumulative production, then draw a parallel line through initial `Pi/Zi`; the x-intercept is OGIP. Source: https://webthesis.biblio.polito.it/7780/1/tesi.pdf
- That same thesis documents pseudo-pressure and variable-rate pseudo-time extensions later in Chapter 2. Those extensions are intentionally not implemented by this preview endpoint.

This evidence is sufficient for the explicitly labelled original flowing-`P/Z` development preview. It is not sufficient to claim that the preview is a validated variable-rate pseudo-time FMB implementation.

## Algorithm Concepts Kept Separate

The following are distinct and must not be collapsed into a simple line fit:

| Concept | Contract decision |
|---|---|
| Ordinary gas material balance `p/z` | Not used; the implemented series uses the selected flowing pressure source. |
| Original flowing material balance | Implemented as a development preview: `x=Gp`, `y=Pflow/Z`, regression slope, then a parallel line through `Pi/Zi`. |
| Material-balance time | Not implemented in this phase. Requires verified formula and units. Frontend labels `pseudotime` as `tca(d)`, but that does not prove the calculation. |
| Pseudo-pressure / pseudo-time | Not implemented in this phase. Requires verified integral definitions, z-factor and viscosity correlations, and units. |
| Linear regression | Implemented only after constructing the flowing `P/Z` series; it is not presented as pseudo-time FMB. |

## Required Algorithm Inputs

These input fields are confirmed by repository/database/frontend contracts:

| Input | Source |
|---|---|
| date | `project_production_data.date` |
| dailyGasProduction | `project_production_data.daily_gas_production` |
| cumulativeGasProduction | `project_production_data.cumulative_gas_production` |
| flowingPressureMpa, node 57 | `well_head_tubing_pressure / 1000000` |
| flowingPressureMpa, node 58 | `calculated_bottom_hole_pressure / 1000000` |
| originalFormationPressureMpa | `project_other_data.original_formation_pressure / 1000000` |
| formationTemperature | `project_other_data.formation_temperature` |
| gas specific gravity | `project_gas_property.specific_gravity` |
| H2S / CO2 / N2 | `project_gas_property.hydrogen_sulfide`, `carbon_dioxide`, `nitrogen` |
| waterGasRatioLimit | Vue FMB trigger sends `0.0602`; FMB input table stores `minimum_water_gas_ratio`; exact filtering role is not proven |

Open algorithm items:

| Item | Status |
|---|---|
| z-factor calculation method | Implemented with Sutton pseudo-critical properties, Wichert–Aziz acid-gas correction, and Dranchuk–Abou-Kassem. Inputs and correlation range are validated. |
| gas viscosity method | Not verified. DB method code exists, UI default label is Lee-Gonzalez-Eakin, but no authoritative local mapping or equation was found. |
| pseudo-pressure requirement | Not verified from primary formula source. |
| boundary-dominated / pseudo-steady-state segment identification | Not verified. |
| zero daily gas handling | Retained because the original preview transform uses cumulative gas and flowing pressure; rate variability is reported as a diagnostic. |
| abnormal-point filtering | Explicit reasons only: invalid cumulative gas, invalid pressure, pressure not below initial pressure, and optional WGR limit. No silent deletion. |
| minimum valid samples | Three points, enforced by the regression module. |
| regression interval selection | All non-excluded points; no automatic boundary-dominated interval invention. |
| OGIP solving equation | `G = -(Pi/Zi) / slope`, using the regression slope and the parallel line through initial `Pi/Zi`. |
| slope/intercept/R validity thresholds | Slope must be negative; OGIP must exceed produced cumulative gas. R² is returned and labelled as preview reliability, not used to manufacture success. |

## Mandatory Quality Gates For Future Implementation

When formula evidence is complete, the pure calculation layer must:

- return original point count, used point count, excluded point count, and reason counts.
- never silently drop rows.
- reject missing or non-finite date, production, pressure, temperature, gas composition, z-factor, viscosity, pseudo-pressure, pseudo-time, transformed x/y, slope, intercept, R/R2, or OGIP values.
- reject non-positive OGIP.
- reject insufficient samples after filtering.
- calculate node type 57 and 58 separately from their own pressure source.
- preserve `wellName`, `nodeType`, `pressureSource`, `pressureKind`, `sourceUnit`, `standardUnit`, `algorithmVersion`, input summary, regression diagnostics, and calculated OGIP.

## Implementation Decision

`mattar-mcneil-flowing-pz-preview-v1` is implemented as a pure in-memory calculation. For each pressure source it:

1. converts the verified Pa pressure source to MPa;
2. calculates `Z` for initial and flowing conditions;
3. constructs real `(cumulative_gas_production, Pflow/Z)` points;
4. records every exclusion and performs least-squares regression;
5. calculates OGIP from the parallel line through `Pi/Zi`;
6. returns independent runtime node 57 and 58 results with UUIDs and `persisted=false`.

The database unit for `cumulative_gas_production` is still not proven by schema metadata. Therefore OGIP remains in the exact database source unit and is never scaled to `10^8 m³` by assumption. No pseudo-time, pseudo-pressure, viscosity, database write, migration, or persistent node is part of this endpoint.

## Verification Boundary

Unit tests verify regression, DAK finite behavior across the X-1 pressure range, separate node sources, runtime identity, and no fallback series. The Docker-network integration test runs the full real path `mysql2 → Repository → calculator → mapper` for X-1 and must be executed on the GRDP host.

The returned OGIP, slope, intercept and R² are real outputs of the documented preview calculation, but remain marked `engineeringValidation=required`. Pseudo-pressure/pseudo-time correctness is outside this algorithm version because those quantities are not produced.
