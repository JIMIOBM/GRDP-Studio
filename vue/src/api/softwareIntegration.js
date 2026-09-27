import axios from 'axios'
import request from '@/utils/request'
import { baseApi } from '../../config/config.default'

// 运行接口使用 201/202 业务包状态；公共 request 目前只接受包内 200/0，
// 因此在软件集成域内使用同源客户端，避免放宽其他业务的响应判断。
const runRequest = axios.create({
  baseURL: baseApi,
  timeout: 15000,
  withCredentials: true
})

runRequest.interceptors.request.use(config => {
  const account = localStorage.getItem('account')
  if (!account) return config
  try {
    const token = JSON.parse(account)?.token
    if (token) config.headers.token = token
  } catch {
    // 无效的本地登录信息由后端按未认证处理。
  }
  return config
})

runRequest.interceptors.response.use(
  response => response.data,
  error => Promise.reject(error.response?.data || error)
)

export const softwareIntegrationApi = {
  getCapabilities: () => request.get('/software-integration/capabilities'),
  listProjects: () => request.get('/software-integration/projects'),
  listDeletedProjects: () => request.get('/software-integration/recycle-bin/projects'),
  getProject: (projectId) => request.get(`/software-integration/projects/${projectId}`),
  createProject: (data) => runRequest.post('/software-integration/projects', data),
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
  createRun: (versionId, study, runType, parameters = null) => runRequest.post(
    `/software-integration/model-versions/${versionId}/runs`,
    { study, runType, parameters }
  ),
  getRun: (runId) => runRequest.get(`/software-integration/runs/${runId}`),
  downloadArtifact: (runId, artifactId) => runRequest.get(
    `/software-integration/runs/${runId}/artifacts/${artifactId}/download`,
    { responseType: 'blob' }
  ),
  downloadArtifactRange: (runId, artifactId, offset, length) => runRequest.get(
    `/software-integration/runs/${runId}/artifacts/${artifactId}/range`,
    { params: { offset, length }, responseType: 'blob' }
  ),
  listRuns: (versionId, limit = 50) => runRequest.get(
    `/software-integration/model-versions/${versionId}/runs`,
    { params: { limit } }
  ),
  cancelRun: (runId) => runRequest.post(`/software-integration/runs/${runId}/cancel`),
  retryRun: (runId) => runRequest.post(`/software-integration/runs/${runId}/retry`),
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
