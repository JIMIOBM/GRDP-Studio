export const supportsPressureScenario = (kind, task) =>
  kind === 'basic_gas' ? ['nodal', 'profile', 'combined'].includes(task) : kind === 'black_oil_liquid' && task === 'nodal'

export const sourceReservoirPressure = version => {
  if (version?.status !== 'READY' || !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(version.modelKind)) return null
  const inspection = version.inspection
  if (!inspection || !['pipesim-well-inspection/1', 'pipesim-well-inspection/2', 'pipesim-well-inspection/3'].includes(inspection.schemaVersion) ||
    Object.keys(inspection).length !== (inspection.schemaVersion.endsWith('/3') ? 4 : inspection.schemaVersion.endsWith('/2') ? 3 : 2)) return null
  const pressure = inspection.reservoirPressure
  if (!pressure || Object.keys(pressure).length !== 2 || pressure.unit !== 'psia' ||
    !Number.isFinite(pressure.value) || pressure.value <= 0 || pressure.value === 1.2345e25) return null
  return pressure.value
}
