'use strict'

const { spawnSync } = require('node:child_process')

function main() {
  const sql = `
SELECT
  COUNT(*) AS production_rows,
  MIN(date) AS first_date,
  MAX(date) AS last_date,
  COUNT(well_head_tubing_pressure) AS wellhead_nonnull,
  SUM(well_head_tubing_pressure > 0) AS wellhead_positive,
  MIN(well_head_tubing_pressure) AS wellhead_min_raw_pa,
  MAX(well_head_tubing_pressure) AS wellhead_max_raw_pa,
  MIN(well_head_tubing_pressure / 1000000) AS wellhead_min_mpa,
  MAX(well_head_tubing_pressure / 1000000) AS wellhead_max_mpa,
  COUNT(calculated_bottom_hole_pressure) AS bottomhole_nonnull,
  SUM(calculated_bottom_hole_pressure > 0) AS bottomhole_positive,
  MIN(calculated_bottom_hole_pressure) AS bottomhole_min_raw_pa,
  MAX(calculated_bottom_hole_pressure) AS bottomhole_max_raw_pa,
  MIN(calculated_bottom_hole_pressure / 1000000) AS bottomhole_min_mpa,
  MAX(calculated_bottom_hole_pressure / 1000000) AS bottomhole_max_mpa,
  AVG(calculated_bottom_hole_pressure / 1000000) AS bottomhole_avg_mpa,
  SUM(calculated_bottom_hole_pressure <= well_head_tubing_pressure) AS bottom_not_above_wellhead_count
FROM project_production_data
WHERE project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1';

SELECT
  original_formation_pressure AS original_raw_pa,
  original_formation_pressure / 1000000 AS original_mpa
FROM project_other_data
WHERE project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1';

SELECT
  date,
  well_head_tubing_pressure AS wellhead_raw_pa,
  well_head_tubing_pressure / 1000000 AS wellhead_mpa,
  calculated_bottom_hole_pressure AS bottomhole_raw_pa,
  calculated_bottom_hole_pressure / 1000000 AS bottomhole_mpa
FROM project_production_data
WHERE project_id=4 AND project_gas_reservoir_id=2 AND well_name='X-1'
ORDER BY date ASC
LIMIT 3;
`
  const result = spawnSync('docker', ['exec', '-i', 'mariadb', 'sh', '-lc', 'mysql --default-character-set=utf8mb4 -uroot -p$MYSQL_ROOT_PASSWORD --batch --raw database'], {
    input: sql,
    encoding: 'utf8',
    maxBuffer: 1024 * 1024 * 16
  })
  if (result.status !== 0) {
    console.error(sanitize(result.stderr || result.stdout))
    process.exit(result.status || 1)
  }
  console.log(result.stdout.trim())
}

function sanitize(value) {
  return String(value).replace(/-p\\S+/g, '-p<redacted>').replace(/MYSQL_ROOT_PASSWORD/g, '<redacted>')
}

main()
