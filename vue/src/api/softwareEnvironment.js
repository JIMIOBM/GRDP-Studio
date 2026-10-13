import { baseApi } from '../../config/config.default'
import { createSoftwareIntegrationClient } from './softwareIntegrationClient'

const client = createSoftwareIntegrationClient({ baseURL: baseApi })
export const softwareEnvironmentApi = {
  inspect: () => client.get('/software-integration/environment', { timeout: 60000 }),
  save: data => client.put('/software-integration/environment', data, {
    timeout: 60000, headers: { 'X-GRDP-Environment': '1' }
  })
}
