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
  status: gasRows.length ? 'data-ready' : 'draft',
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

// 顶部按钮是新增编号的唯一入口；最新记录仍为空白草稿时直接复用。
export const createOrReusePvtRecord = (
  projectId,
  gasReservoirId,
  wellName,
  initialGasRows = []
) => {
  const store = readStore(projectId, gasReservoirId)
  const records = Array.isArray(store[wellName]) ? store[wellName] : []
  const sortedRecords = [...records].sort(
    (left, right) => Number(left.index) - Number(right.index)
  )
  const latestRecord = sortedRecords[sortedRecords.length - 1]

  if (latestRecord?.status === 'draft') {
    return { record: clone(latestRecord), created: false, reused: true }
  }

  const nextIndex = sortedRecords.reduce(
    (maximum, record) => Math.max(maximum, Number(record.index) || 0),
    0
  ) + 1
  const record = createRecord(wellName, nextIndex, initialGasRows)
  records.push(record)
  store[wellName] = records.sort((left, right) => Number(left.index) - Number(right.index))
  writeStore(projectId, gasReservoirId, store)
  return { record: clone(record), created: true, reused: false }
}

// 导入成功后立即更新指定编号，保证未计算时刷新页面也能恢复导入内容。
export const savePvtImport = ({
  projectId,
  gasReservoirId,
  wellName,
  currentIndex,
  kind,
  importKind,
  rows
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
  if (importKind === 'result') {
    record[resultKey] = clone(rows)
    record.lastCalculatedKind = kind
    record.status = 'calculated'
  } else {
    record[inputKey] = clone(rows)
    // 基础数据变化后清空同类旧结果，避免展示与输入不匹配的曲线。
    record[resultKey] = []
    record.lastCalculatedKind = kind
    record.status = 'data-ready'
  }

  record.updatedAt = new Date().toISOString()
  store[wellName] = records.sort((left, right) => Number(left.index) - Number(right.index))
  writeStore(projectId, gasReservoirId, store)
  return clone(record)
}

// 计算只覆盖当前编号，参数变化不再触发新节点。
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

  record[inputKey] = clone(inputRows)
  record[resultKey] = clone(resultRows)
  record[settingsKey] = clone(settings)
  record.lastCalculatedKind = kind
  record.status = 'calculated'
  record.updatedAt = new Date().toISOString()
  store[wellName] = records.sort((left, right) => Number(left.index) - Number(right.index))
  writeStore(projectId, gasReservoirId, store)

  return { record: clone(record), created: false }
}
