const STORAGE_VERSION = 1
const STORAGE_PREFIX = `grdp-productivity-nodes-v${STORAGE_VERSION}`

const storageKey = (projectId, gasReservoirId) =>
  `${STORAGE_PREFIX}:${projectId}:${gasReservoirId}`

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

export const rememberModifiedIsochronalNode = (projectId, gasReservoirId, wellName) => {
  if (!wellName) return
  const store = readStore(projectId, gasReservoirId)
  store[wellName] = {
    ...(store[wellName] || {}),
    modifiedIsochronal: true,
    updatedAt: new Date().toISOString()
  }
  writeStore(projectId, gasReservoirId, store)
}

export const hasRememberedModifiedIsochronalNode = (projectId, gasReservoirId, wellName) =>
  Boolean(readStore(projectId, gasReservoirId)?.[wellName]?.modifiedIsochronal)

export const getRememberedModifiedIsochronalWells = (projectId, gasReservoirId) => {
  const store = readStore(projectId, gasReservoirId)
  return Object.keys(store).filter(wellName => store[wellName]?.modifiedIsochronal)
}
