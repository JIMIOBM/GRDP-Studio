import request from '@/utils/request'
const base = '/pipeline-capacity'
export const pipelineCapacityApi = {
  calculateBatch: data => request.post(`${base}/batch/calculate`, data, { timeout: 600000 }),
  saveBatch: data => request.post(`${base}/batch/save`, data, { timeout: 60000 }),
  latestBatch: context => request.get(`${base}/batch/latest`, { params: context }),
  pvtModel: context => request.get(`${base}/pvt-model`, { params: context }),
  savePvtModel: data => request.put(`${base}/pvt-model`, data),
  gasProperties: context => request.get(`${base}/gas-properties`, { params: context }),
  gasPropertyComponents: () => request.get(`${base}/gas-properties/components`),
  gasPropertyParameters: context => request.get(`${base}/gas-properties/parameters`, { params: context }),
  pvtComposition: context => request.get(`${base}/pvt-composition`, { params: context }),
  calculateGasProperty: data => request.post(`${base}/gas-properties/calculate`, data),
  saveGasProperties: data => request.put(`${base}/gas-properties`, data),
  temperature: context => request.get(`${base}/temperature`, { params: context }),
  temperatureProperties: context => request.get(`${base}/temperature/properties`, { params: context }),
  saveTemperature: data => request.put(`${base}/temperature`, data),
  calculateTemperature: (data, kind) => request.post(`${base}/temperature/calculate/${kind}`, data),
  solveTemperature: data => request.post(`${base}/temperature/solve`, data, {timeout:60000}),
  topology: context => request.get(`${base}/topology`, { params: context }),
  saveTopology: data => request.put(`${base}/topology`, data),
  model: context => request.get(`${base}/model`, { params: context }),
  saveSection: (scope, data) => request.patch(`${base}/model/${scope}`, data)
}
