const recordOrder = record => {
  const pvtNo = Number(record?.pvtNo)
  if (Number.isSafeInteger(pvtNo) && pvtNo > 0) return pvtNo
  const pvtId = Number(record?.pvtId)
  return Number.isSafeInteger(pvtId) && pvtId > 0 ? pvtId : 0
}

export const selectDefaultPvtRecord = (records, preferredPvtId = null) => {
  if (!Array.isArray(records) || records.length === 0) return null

  if (preferredPvtId !== null && preferredPvtId !== undefined && preferredPvtId !== '') {
    const preferred = records.find(record => String(record?.pvtId) === String(preferredPvtId))
    if (preferred) return preferred
  }

  const calculated = records.filter(record =>
    record?.status === 'calculated' || record?.lastCalculatedKind === 'gas'
  )
  const candidates = calculated.length ? calculated : records
  return [...candidates].sort((left, right) => recordOrder(right) - recordOrder(left))[0]
}
