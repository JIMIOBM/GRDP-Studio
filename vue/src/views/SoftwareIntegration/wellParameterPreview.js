export const supportsPressureScenario = (kind, task) =>
  kind === 'basic_gas' ? ['nodal', 'profile', 'combined'].includes(task) : kind === 'black_oil_liquid' && task === 'nodal'

export const sourceReservoirPressure = version => {
  if (version?.status !== 'READY' || !['black_oil_liquid', 'basic_gas', 'legacy_well'].includes(version.modelKind)) return null
  const inspection = version.inspection
  if (!inspection || !['pipesim-well-inspection/1', 'pipesim-well-inspection/2', 'pipesim-well-inspection/3', 'pipesim-well-inspection/4', 'pipesim-well-inspection/5', 'pipesim-well-inspection/6', 'pipesim-well-inspection/7', 'pipesim-well-inspection/8', 'pipesim-well-inspection/9'].includes(inspection.schemaVersion)) return null
  const schema = inspection.schemaVersion
  const fields = schema.endsWith('/9') ? 7 + Number(Object.hasOwn(inspection, 'networkCapability'))
    : schema.endsWith('/8') ? 6 + Number(Object.hasOwn(inspection, 'networkCapability'))
    : schema.endsWith('/7') ? 7 : ['/5', '/6'].some(version => schema.endsWith(version)) ? 6
    : schema.endsWith('/4') ? 5 : schema.endsWith('/3') ? 4 : schema.endsWith('/2') ? 3 : 2
  if (Object.keys(inspection).length !== fields) return null
  const pressure = inspection.reservoirPressure
  if (!pressure || Object.keys(pressure).length !== 2 || pressure.unit !== 'psia' ||
    !Number.isFinite(pressure.value) || pressure.value <= 0 || pressure.value === 1.2345e25) return null
  return pressure.value
}
