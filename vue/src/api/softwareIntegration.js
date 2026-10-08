import router from '@/router'
import { baseApi } from '../../config/config.default'
import { disconnectNotifySocket } from '@/utils/notifySocket'
import { createSoftwareIntegrationClient } from './softwareIntegrationClient'

const request = createSoftwareIntegrationClient({
  baseURL: baseApi,
  onUnauthorized: () => {
    disconnectNotifySocket()
    localStorage.removeItem('account')
    router.push('/login')
  }
})

export const softwareIntegrationApi = {
  getCapabilities: () => request.get('/software-integration/capabilities'),
  listProjects: () => request.get('/software-integration/projects'),
  listDeletedProjects: () => request.get('/software-integration/recycle-bin/projects'),
  getProject: (projectId) => request.get(`/software-integration/projects/${projectId}`),
  createProject: (data) => request.post('/software-integration/projects', data),
  updateProject: (projectId, data) => request.put(`/software-integration/projects/${projectId}`, data),
  deleteProject: (projectId) => request.delete(`/software-integration/projects/${projectId}`),
  deleteModel: (projectId, modelId) => request.delete(`/software-integration/projects/${projectId}/models/${modelId}`),
  restoreProject: (projectId) => request.post(`/software-integration/recycle-bin/projects/${projectId}/restore`),
  revalidateModel: (projectId, versionId) => request.post(`/software-integration/projects/${projectId}/model-versions/${versionId}/validate`),
  inspectModelArchive: (projectId, file) => {
    const data = new FormData()
    data.append('file', file)
    return request.post(`/software-integration/projects/${projectId}/model-archives/inspect`, data, {
      timeout: 10 * 60 * 1000
    })
  },
  createRun: (versionId, study, runType, parameters = null) => request.post(
    `/software-integration/model-versions/${versionId}/runs`,
    { study, runType, parameters }
  ),
  getRun: (runId) => request.get(`/software-integration/runs/${runId}`),
  downloadArtifact: (runId, artifactId) => request.get(
    `/software-integration/runs/${runId}/artifacts/${artifactId}/download`,
    { responseType: 'blob' }
  ),
  downloadArtifactRange: (runId, artifactId, offset, length) => request.get(
    `/software-integration/runs/${runId}/artifacts/${artifactId}/range`,
    { params: { offset, length }, responseType: 'blob' }
  ),
  listRuns: (versionId, limit = 50) => request.get(
    `/software-integration/model-versions/${versionId}/runs`,
    { params: { limit } }
  ),
  cancelRun: (runId) => request.post(`/software-integration/runs/${runId}/cancel`),
  retryRun: (runId) => request.post(`/software-integration/runs/${runId}/retry`),
  uploadModel: (projectId, file, mainFile = null) => {
    const data = new FormData()
    data.append('file', file)
    if (mainFile) data.append('mainFile', mainFile)
    return request.post(`/software-integration/projects/${projectId}/models`, data, {
      // Let the browser attach the multipart boundary automatically.
      timeout: 10 * 60 * 1000
    })
  }
}
