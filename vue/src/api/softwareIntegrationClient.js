import axios from 'axios'

const acceptedCodes = new Set([0, 200, 201, 202])

// Software integration owns its feedback. Transport and business errors share
// one rejection path; polling must never create a toast or resubmit a task.
export const createSoftwareIntegrationClient = ({ baseURL, onUnauthorized }) => {
  const client = axios.create({ baseURL, timeout: 15000, withCredentials: true })
  let unauthorizedHandled = false
  let authenticatedToken = null
  const rejectResponse = (error, unauthorized = false) => {
    if ((error?.code === 401 || unauthorized) && !unauthorizedHandled) {
      unauthorizedHandled = true
      onUnauthorized?.()
    }
    return Promise.reject(error)
  }

  client.interceptors.request.use(config => {
    try {
      const token = JSON.parse(globalThis.localStorage?.getItem('account') || 'null')?.token
      if (token) {
        if (token !== authenticatedToken) unauthorizedHandled = false
        authenticatedToken = token
        config.headers.token = token
      }
    } catch {
      // Invalid browser state is still authenticated by the server.
    }
    return config
  })
  client.interceptors.response.use(
    response => {
      const body = response.data
      if (body && typeof body === 'object' && typeof body.code === 'number') {
        if (!acceptedCodes.has(body.code)) return rejectResponse(body)
      }
      return body
    },
    error => rejectResponse(error?.response?.data || error, error?.response?.status === 401)
  )
  return client
}
