# FlowBalance Database Mapping

Scope: read-only mapping for `projectId=4`, `gasReservoirId=2`, `wellName=X-1`.

Database target:

| Item | Value |
|---|---|
| grdp-server dependency | `mariadb` |
| Database name | `database` |
| Credential output | Redacted / not recorded |

Read-only evidence used:

| Evidence source | Result |
|---|---|
| `information_schema.columns.COLUMN_COMMENT` | Empty for mapped columns |
| `information_schema.tables.TABLE_COMMENT` | Empty for mapped tables |
| Local backend/interface/page label search | No matching unit evidence found |
| Unit inference from numeric range | Not used |

Target row counts:

| Table | Target filter | Target rows |
|---|---|---:|
| `project_production_data` | `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` | 3556 |
| `project_static_pressure_data` | `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` | 0 |
| `project_gas_static_pressure_data` | `gas_reservoir_id=2` | 0 |
| `project_achieve_the_recover_static_pressure_of_gas_well` | `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` | 17 |
| `project_gas_reservoir` | `id=2 AND project_id=4` | 1 |
| `project_gas_property` | `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` | 1 |
| `project_other_data` | `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` | 1 |

## Final Mapping

| FlowBalance字段 | 真实表 | 真实字段 | 类型 | 单位 | 非空数量 | 最小值 | 最大值 | 转换规则 | 来源层级 | 备注 |
|---|---|---|---|---|---:|---:|---:|---|---|---|
| production.date | `project_production_data` | `date` | `timestamp` | TODO | 3556 | `2004-04-21 00:00:00` | `2014-01-14 00:00:00` | TODO | single well | Filter: `project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'` |
| production.dailyGas | `project_production_data` | `daily_gas_production` | `double` | TODO | 3556 | TODO | TODO | TODO | single well | Confirmed field; positive count previously observed as 3126 |
| production.cumulativeGas | `project_production_data` | `cumulative_gas_production` | `double` | TODO | 3556 | TODO | TODO | TODO | single well | Confirmed field |
| production.waterGasRatio | `project_production_data` | `water_gas_ratio` | `double` | TODO | TODO | TODO | TODO | TODO | single well | Confirmed field |
| production.projectId | `project_production_data` | `project_id` | `bigint` | n/a | 3556 | 4 | 4 | none | project | FK to `project_summaries.id` |
| production.gasReservoirId | `project_production_data` | `project_gas_reservoir_id` | `bigint` | n/a | 3556 | 2 | 2 | none | gas reservoir | FK to `project_gas_reservoir.id` |
| production.wellName | `project_production_data` | `well_name` | `varchar(255)` | n/a | 3556 | n/a | n/a | none | single well | Filter value `X-1` |
| nodeType57.WELLHEAD.pressureCandidate | `project_production_data` | `well_head_tubing_pressure` | `double` | TODO | 3556 | 7080000 | 24060000 | TODO | single well | Column comment empty; table comment empty; `>0` count 3556; not enough evidence to confirm nodeType57 |
| nodeType57.WELLHEAD.casingPressureCandidate | `project_production_data` | `well_head_casing_pressure` | `double` | TODO | TODO | TODO | TODO | TODO | single well | Field exists, but final requested statistics were only for `well_head_tubing_pressure` |
| nodeType57.WELLHEAD.tubingTemperatureCandidate | `project_production_data` | `well_head_tubing_temperature` | `double` | TODO | 3556 | 275.54999999999995 | 315.39 | TODO | single well | Column comment empty; unit TODO |
| nodeType57.WELLHEAD.casingTemperatureCandidate | `project_production_data` | `well_head_casing_temperature` | `double` | TODO | 3556 | 273.15 | 308.25 | TODO | single well | Column comment empty; unit TODO |
| nodeType58.BOTTOMHOLE.measuredPressureCandidate | `project_production_data` | `measured_bottom_hole_pressure` | `double` | TODO | 0 | NULL | NULL | none | single well | Column exists but has no X-1 non-null values; do not use as available measured data |
| nodeType58.BOTTOMHOLE.calculatedPressureCandidate | `project_production_data` | `calculated_bottom_hole_pressure` | `double` | TODO | 3556 | 11226379.42819062 | 33689238.777410485 | TODO | single well | `>0` count 3556; do not add implicit fallback without business confirmation |
| nodeType58.BOTTOMHOLE.temperature | TODO | TODO | TODO | TODO | 0 | NULL | NULL | TODO | TODO | No bottom-hole temperature field found in mapped pressure/production tables |
| pressure.static.pressure | `project_static_pressure_data` | `reservior_pressure` | `double` | TODO | 0 | NULL | NULL | TODO | single well | Table exists; no target X-1 rows; field name uses database spelling `reservior_pressure` |
| pressure.gasReservoirStatic.pressure | `project_gas_static_pressure_data` | `reservior_pressure` | `double` | TODO | 0 | NULL | NULL | TODO | gas reservoir | Table exists; no target reservoir 2 rows; field name uses database spelling `reservior_pressure` |
| pressure.recover.calculatedBottomHolePressure | `project_achieve_the_recover_static_pressure_of_gas_well` | `calculated_bottom_hole_pressure` | `double` | TODO | 17 | TODO | TODO | TODO | single well | Candidate static/recovered pressure table; whether FlowBalance should use it requires business/source confirmation |
| reservoir.originalPressure.primaryCandidate | `project_gas_reservoir` | `original_formation_pressure_of_gas_reservoir` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO; column comment empty |
| reservoir.temperature.primaryCandidate | `project_gas_reservoir` | `formation_temperature_of_gas_reservoir` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO; column comment empty |
| reservoir.OGIP.primaryCandidate | `project_gas_reservoir` | `ogip` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO; column comment empty |
| reservoir.waterSaturation.primaryCandidate | `project_gas_reservoir` | `average_irreducible_water_saturation` | `double` | TODO | 1 | 0.32053144906189435 | 0.32053144906189435 | TODO | gas reservoir | Status POSITIVE; column comment empty |
| reservoir.rockCompressibility.primaryCandidate | `project_gas_reservoir` | `rock_compressibility` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO; column comment empty |
| reservoir.waterCompressibility.primaryCandidate | `project_gas_reservoir` | `formation_water_compressibility` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO; column comment empty |
| reservoir.specificGravity.primaryCandidate | `project_gas_reservoir` | `specific_gravity` | `double` | TODO | 1 | 0.5 | 0.5 | TODO | gas reservoir | Gas-reservoir-level value |
| reservoir.zFactor.primaryCandidate | `project_gas_reservoir` | `deviation_coefficient_of_natural_gas_under_original_conditions` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO |
| reservoir.gasViscosity.primaryCandidate | `project_gas_reservoir` | `viscosity_of_natural_gas_under_original_conditions` | `double` | TODO | 1 | 0 | 0 | TODO | gas reservoir | Status ZERO |
| otherData.originalPressure.candidate | `project_other_data` | `original_formation_pressure` | `double` | TODO | 1 | 50000000 | 50000000 | TODO | single well | Status POSITIVE; field semantics differ from gas-reservoir-level source |
| otherData.temperature.candidate | `project_other_data` | `formation_temperature` | `double` | TODO | 1 | 353.15 | 353.15 | TODO | single well | Status POSITIVE; field semantics differ from gas-reservoir-level source |
| otherData.OGIP.candidate | `project_other_data` | `single_well_original_gas_inplace` | `double` | TODO | 0 | NULL | NULL | TODO | single well | Status NULL |
| otherData.waterSaturation | `project_other_data` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| otherData.rockCompressibility | `project_other_data` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| otherData.waterCompressibility | `project_other_data` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.specificGravity.candidate | `project_gas_property` | `specific_gravity` | `double` | TODO | 1 | 0.58 | 0.58 | TODO | single well | X-1 gas-property-level value |
| gasProperty.hydrogenSulfide | `project_gas_property` | `hydrogen_sulfide` | `double` | TODO | 1 | 0.0462 | 0.0462 | TODO | single well | Actual gas composition field |
| gasProperty.carbonDioxide | `project_gas_property` | `carbon_dioxide` | `double` | TODO | 1 | 0.039599999999999996 | 0.039599999999999996 | TODO | single well | Actual gas composition field |
| gasProperty.nitrogen | `project_gas_property` | `nitrogen` | `double` | TODO | 1 | 0 | 0 | TODO | single well | Actual gas composition field; status ZERO |
| gasProperty.originalPressure | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.formationTemperature | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.OGIP | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.waterSaturation | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.rockCompressibility | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |
| gasProperty.waterCompressibility | `project_gas_property` | TODO | TODO | TODO | 0 | NULL | NULL | TODO | single well | No matching field in this table |

## Confirmed Join Relationships

| From table | Field | To table | Field |
|---|---|---|---|
| `project_production_data` | `project_gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_production_data` | `project_id` | `project_summaries` | `id` |
| `project_static_pressure_data` | `project_gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_static_pressure_data` | `project_id` | `project_summaries` | `id` |
| `project_gas_static_pressure_data` | `gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_achieve_the_recover_static_pressure_of_gas_well` | `project_gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_achieve_the_recover_static_pressure_of_gas_well` | `project_id` | `project_summaries` | `id` |
| `project_gas_property` | `project_gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_gas_property` | `project_id` | `project_summaries` | `id` |
| `project_other_data` | `project_gas_reservoir_id` | `project_gas_reservoir` | `id` |
| `project_other_data` | `project_id` | `project_summaries` | `id` |
| `project_gas_reservoir` | `project_id` | `project_summaries` | `id` |

## Final Conclusions

| Topic | Conclusion |
|---|---|
| nodeType57 | Not confirmed. `well_head_tubing_pressure` has 3556 non-null positive rows, but database comments and local labels do not explicitly prove it is the FlowBalance nodeType57/WELLHEAD source. |
| nodeType58 | Not confirmed. `measured_bottom_hole_pressure` has 0 non-null rows. `calculated_bottom_hole_pressure` has data, but it must not be used as an implicit fallback without business confirmation. |
| Units | TODO for all pressure, temperature, production, OGIP, compressibility, and composition fields. No column comments, table comments, backend/interface docs, or page labels provided explicit unit evidence. |
| Reservoir parameter priority | Not determinable from database alone. `project_gas_reservoir` is gas-reservoir-level but has several ZERO values; `project_other_data` has single-well pressure/temperature candidates. Priority requires business/source confirmation. |
