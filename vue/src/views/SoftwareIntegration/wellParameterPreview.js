export const sourceReservoirPressure = version => {
  if (version?.status !== 'READY' || !['black_oil_liquid', 'basic_gas'].includes(version.modelKind)) return null
  const inspection = version.inspection
  if (!inspection || Object.keys(inspection).length !== 2 || inspection.schemaVersion !== 'pipesim-well-inspection/1') return null
  const pressure = inspection.reservoirPressure
  if (!pressure || Object.keys(pressure).length !== 2 || pressure.unit !== 'psia' ||
    !Number.isFinite(pressure.value) || pressure.value <= 0 || pressure.value === 1.2345e25) return null
  return pressure.value
}
