const STORAGE_VERSION = 1
const STORAGE_PREFIX = `grdp-pvt-records-v${STORAGE_VERSION}`

const clone = value => JSON.parse(JSON.stringify(value))

const storageKey = (projectId, gasReservoirId) =>
  `${STORAGE_PREFIX}:${projectId}:${gasReservoirId}`

// 按项目和气藏隔离浏览器持久化数据，单口井的多个编号保存在同一数组中。
const readStore = (projectId, gasReservoirId) => {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey(projectId, gasReservoirId)) || '{}')
    return value && typeof value === 'object' ? value : {}
  } catch {
    return {}
  }
}

const writeStore = (projectId, gasReservoirId, store) => {
  localStorage.setItem(storageKey(projectId, gasReservoirId), JSON.stringify(store))
}

const defaultWaterRows = () => [[25000, 40, 119.85]]

const createRecord = (wellName, index, gasRows = []) => ({
  wellName,
  index,
  gasRows: clone(gasRows),
  waterRows: defaultWaterRows(),
  gasResultRows: [],
  waterResultRows: [],
  gasSettings: {},
  waterSettings: {},
  lastCalculatedKind: '',
  createdAt: new Date().toISOString(),
  updatedAt: new Date().toISOString()
})

export const getPvtRecords = (projectId, gasReservoirId, wellName) => {
  const store = readStore(projectId, gasReservoirId)
  return clone(Array.isArray(store[wellName]) ? store[wellName] : [])
    .sort((left, right) => Number(left.index) - Number(right.index))
}

export const getPvtRecord = (projectId, gasReservoirId, wellName, index) =>
  getPvtRecords(projectId, gasReservoirId, wellName)
    .find(record => Number(record.index) === Number(index)) || null

export const ensurePvtSourceRecord = (
  projectId,
  gasReservoirId,
  wellName,
  gasRows
) => {
  const store = readStore(projectId, gasReservoirId)
  const records = Array.isArray(store[wellName]) ? store[wellName] : []
  let firstRecord = records.find(record => Number(record.index) === 1)

  if (!firstRecord) {
    // 汇总接口首次返回该井时只创建 PVT性质1，不覆盖已有计算记录。
    firstRecord = createRecord(wellName, 1, gasRows)
    records.push(firstRecord)
  } else if (!firstRecord.gasResultRows?.length && !firstRecord.waterResultRows?.length) {
    firstRecord.gasRows = clone(gasRows)
    firstRecord.updatedAt = new Date().toISOString()
  }

  store[wellName] = records
  writeStore(projectId, gasReservoirId, store)
  return clone(firstRecord)
}

const sameInput = (left, right) =>
  JSON.stringify(left || []) === JSON.stringify(right || [])

// 同一输入重复计算覆盖当前编号；已有结果且输入改变时创建下一个编号。
export const savePvtCalculation = ({
  projectId,
  gasReservoirId,
  wellName,
  currentIndex,
  kind,
  inputRows,
  resultRows,
  settings = {}
}) => {
  const store = readStore(projectId, gasReservoirId)
  const records = Array.isArray(store[wellName]) ? store[wellName] : []
  let record = records.find(item => Number(item.index) === Number(currentIndex))

  if (!record) {
    record = createRecord(wellName, Number(currentIndex) || 1)
    records.push(record)
  }

  const inputKey = kind === 'water' ? 'waterRows' : 'gasRows'
  const resultKey = kind === 'water' ? 'waterResultRows' : 'gasResultRows'
  const settingsKey = kind === 'water' ? 'waterSettings' : 'gasSettings'
  const alreadyCalculated = Boolean(
    record.gasResultRows?.length || record.waterResultRows?.length
  )
  const inputChanged = !sameInput(record[inputKey], inputRows)
  let created = false

  if (alreadyCalculated && inputChanged) {
    // 新记录复制另一类基础输入，但不复制旧曲线，避免新旧结果混用。
    const nextIndex = records.reduce(
      (maximum, item) => Math.max(maximum, Number(item.index) || 0),
      0
    ) + 1
    record = createRecord(wellName, nextIndex, record.gasRows || [])
    record.waterRows = clone(records.find(
      item => Number(item.index) === Number(currentIndex)
    )?.waterRows || defaultWaterRows())
    records.push(record)
    created = true
  }

  record[inputKey] = clone(inputRows)
  record[resultKey] = clone(resultRows)
  record[settingsKey] = clone(settings)
  record.lastCalculatedKind = kind
  record.updatedAt = new Date().toISOString()
  // 先完成持久化再通知树更新，保证新页面挂载时可以立即读取完整结果。
  store[wellName] = records.sort((left, right) => Number(left.index) - Number(right.index))
  writeStore(projectId, gasReservoirId, store)

  return { record: clone(record), created }
}
