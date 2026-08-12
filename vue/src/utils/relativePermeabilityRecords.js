const STORAGE_VERSION = 1
const STORAGE_PREFIX = `grdp-relative-permeability-records-v${STORAGE_VERSION}`

const clone = value => JSON.parse(JSON.stringify(value))

const storageKey = (projectId, gasReservoirId) =>
  `${STORAGE_PREFIX}:${projectId}:${gasReservoirId}`

const readStore = (projectId, gasReservoirId) => {
  try {
    const value = JSON.parse(
      localStorage.getItem(storageKey(projectId, gasReservoirId)) || '{}'
    )
    return value && typeof value === 'object' ? value : {}
  } catch {
    return {}
  }
}

const writeStore = (projectId, gasReservoirId, store) => {
  localStorage.setItem(storageKey(projectId, gasReservoirId), JSON.stringify(store))
}

const recordKey = (wellName, index) => `${wellName}:${Number(index) || 1}`

export const getRelativePermeabilityRecord = (
  projectId,
  gasReservoirId,
  wellName,
  index
) => {
  const store = readStore(projectId, gasReservoirId)
  const record = store[recordKey(wellName, index)]
  return record ? clone(record) : null
}

export const getRelativePermeabilityRecords = (projectId, gasReservoirId, wellName) => {
  const store = readStore(projectId, gasReservoirId)
  const prefix = `${wellName}:`
  return Object.entries(store)
    .filter(([key, record]) => key.startsWith(prefix) && record?.wellName === wellName)
    .map(([, record]) => clone(record))
    .sort((left, right) => Number(left.index) - Number(right.index))
}

export const getNextRelativePermeabilityIndex = (projectId, gasReservoirId, wellName) =>
  getRelativePermeabilityRecords(projectId, gasReservoirId, wellName).reduce(
    (maximum, record) => Math.max(maximum, Number(record.index) || 0),
    0
  ) + 1

export const saveRelativePermeabilityRecord = ({
  projectId,
  gasReservoirId,
  wellName,
  index,
  rows
}) => {
  const store = readStore(projectId, gasReservoirId)
  const key = recordKey(wellName, index)
  const existingRecord = store[key]
  const now = new Date().toISOString()
  const record = {
    wellName,
    index: Number(index) || 1,
    rows: clone(rows),
    createdAt: existingRecord?.createdAt || now,
    updatedAt: now
  }

  store[key] = record
  writeStore(projectId, gasReservoirId, store)
  return clone(record)
}
