import request from '@/utils/request'

const path = (projectId, id = '') => `/software-integration/projects/${projectId}/template-creations${id ? `/${id}` : ''}`
export const templateCreationApi = {
  list: projectId => request.get(path(projectId), { timeout: 15000 }),
  capabilities: projectId => request.get(`${path(projectId)}/capabilities`, { timeout: 45000 }),
  create: (projectId, data) => request.post(path(projectId), data),
  get: (projectId, id) => request.get(path(projectId, id), { timeout: 45000 }),
  cancel: (projectId, id) => request.post(`${path(projectId, id)}/cancel`, {}, { timeout: 90000 }),
  register: (projectId, id) => request.post(`${path(projectId, id)}/register`, {}, { timeout: 120000 }),
  download: (projectId, id) => request.get(`${path(projectId, id)}/model`, { responseType: 'blob', timeout: 60000 })
}
