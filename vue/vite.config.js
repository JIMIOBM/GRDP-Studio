import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const parseEnvValue = (value) => {
  const trimmed = value.trim()
  if (
    (trimmed.startsWith('"') && trimmed.endsWith('"')) ||
    (trimmed.startsWith("'") && trimmed.endsWith("'"))
  ) {
    return trimmed.slice(1, -1)
  }
  return trimmed
}

const getLocalEnvValue = (key) => {
  const value = process.env[key]
  return value ? parseEnvValue(value) : ''
}

const getCookiePair = (cookieHeader, name) => {
  return String(cookieHeader || '')
    .split(';')
    .map(item => item.trim())
    .find(item => item.startsWith(`${name}=`)) || ''
}

const parseJsonBody = (req) => new Promise((resolveBody, rejectBody) => {
  let body = ''
  req.on('data', chunk => {
    body += chunk
  })
  req.on('end', () => {
    try {
      resolveBody(body ? JSON.parse(body) : {})
    } catch (error) {
      rejectBody(error)
    }
  })
  req.on('error', rejectBody)
})

const getSetCookieHeaders = (headers) => {
  if (typeof headers.getSetCookie === 'function') return headers.getSetCookie()
  const setCookie = headers.get('set-cookie')
  return setCookie ? [setCookie] : []
}

const toCookieHeader = (setCookieHeaders) => {
  const headers = Array.isArray(setCookieHeaders) ? setCookieHeaders : [setCookieHeaders].filter(Boolean)
  return headers
    .flatMap(header => String(header).split(/,(?=\s*[^;,]+=)/))
    .map(cookie => cookie.split(';')[0].trim())
    .filter(Boolean)
    .join('; ')
}

const getCsrfToken = (flow) => {
  const nodes = flow?.ui?.nodes || []
  for (const node of nodes) {
    const attrs = node?.attributes || {}
    if (attrs.name === 'csrf_token' && attrs.value) return attrs.value
  }
  return ''
}

const verifyDockerSession = async (cookie, baseUrl) => {
  const sessionPath = getLocalEnvValue('DOCKER_SESSION_CHECK_PATH') || '/services/ory/kratos/sessions/whoami'
  const response = await fetch(new URL(sessionPath, baseUrl), {
    headers: {
      Accept: 'application/json',
      Cookie: cookie
    },
    redirect: 'manual'
  })

  if (!response.ok) {
    const error = new Error(`原平台会话校验失败：${response.status}`)
    error.status = response.status
    throw error
  }

  const session = await response.json()
  if (session?.active === false) {
    const error = new Error('原平台会话未激活')
    error.status = 401
    throw error
  }
  return session
}

const initDockerLoginFlow = async () => {
  const baseUrl = getLocalEnvValue('DOCKER_AUTH_BASE_URL') || 'http://127.0.0.1:9919'
  const loginInitPath = getLocalEnvValue('DOCKER_LOGIN_INIT_PATH') || '/services/ory/kratos/self-service/login/browser'
  const response = await fetch(new URL(loginInitPath, baseUrl), {
    headers: {
      Accept: 'application/json'
    },
    redirect: 'manual'
  })

  const initCookie = toCookieHeader(getSetCookieHeaders(response.headers))

  if (response.ok) {
    return { flow: await response.json(), initCookie, baseUrl }
  }

  const location = response.headers.get('location')
  if (response.status >= 300 && response.status < 400 && location) {
    const flowId = new URL(location, baseUrl).searchParams.get('flow')
    if (!flowId) throw new Error('原平台登录初始化未返回 flow')

    const flowResponse = await fetch(
      new URL(`/services/ory/kratos/self-service/login/flows?id=${encodeURIComponent(flowId)}`, baseUrl),
      {
        headers: {
          Accept: 'application/json',
          ...(initCookie ? { Cookie: initCookie } : {})
        }
      }
    )

    if (!flowResponse.ok) {
      throw new Error(`原平台登录 flow 获取失败：${flowResponse.status}`)
    }

    return { flow: await flowResponse.json(), initCookie, baseUrl }
  }

  throw new Error(`原平台登录初始化失败：${response.status}`)
}

const loginDockerPlatform = async ({ username, password }) => {
  if (!username || !password) {
    throw new Error('缺少原平台登录用户名或密码')
  }

  const { flow, initCookie, baseUrl } = await initDockerLoginFlow()
  const csrfToken = getCsrfToken(flow)
  const flowId = flow?.id
  if (!flowId || !csrfToken) {
    throw new Error('原平台登录 flow 缺少 flowId 或 csrf_token')
  }

  const loginPath = getLocalEnvValue('DOCKER_LOGIN_SUBMIT_PATH') || '/services/ory/kratos/self-service/login'
  const form = new URLSearchParams()
  form.set('csrf_token', csrfToken)
  form.set('method', getLocalEnvValue('DOCKER_LOGIN_METHOD_NAME') || 'password')
  form.set(getLocalEnvValue('DOCKER_LOGIN_USERNAME_FIELD') || 'identifier', username)
  form.set(getLocalEnvValue('DOCKER_LOGIN_PASSWORD_FIELD') || 'password', password)

  const response = await fetch(new URL(`${loginPath}?flow=${encodeURIComponent(flowId)}`, baseUrl), {
    method: 'POST',
    headers: {
      Accept: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      'Content-Type': 'application/x-www-form-urlencoded',
      ...(initCookie ? { Cookie: initCookie } : {})
    },
    body: form.toString(),
    redirect: 'manual'
  })

  const loginCookie = toCookieHeader(getSetCookieHeaders(response.headers))
  if (response.status !== 303 && !response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(text || `原平台登录失败：${response.status}`)
  }

  const cookie = [initCookie, loginCookie].filter(Boolean).join('; ')
  if (!cookie || !cookie.includes('ahksoil_identity_session')) {
    throw new Error('原平台登录成功但没有拿到 ahksoil_identity_session')
  }

  const session = await verifyDockerSession(cookie, baseUrl)
  return {
    cookie,
    identityCookie: getCookiePair(cookie, 'ahksoil_identity_session'),
    expiresAt: session?.expires_at || null
  }
}

export default defineConfig(() => {
  let dockerSessionCookie = ''

  const logDockerProxyRequest = (proxyReq, req) => {
    if (!/waterinvasionanalysis|common\/notify/.test(req.url || '')) return

    const cookie = proxyReq.getHeader('cookie') || ''
    console.log('[docker-proxy]', req.method, req.url, {
      targetPath: req.url?.replace(/^\/docker-api/, '/api'),
      hasCookie: Boolean(cookie),
      hasIdentitySession: String(cookie).includes('ahksoil_identity_session'),
      origin: proxyReq.getHeader('origin'),
      processEnv: proxyReq.getHeader('process-env')
    })
  }
  const alignDockerOrigin = (proxyReq) => {
    proxyReq.setHeader('Origin', 'http://127.0.0.1:9920')
  }
  const applyDockerHeaders = (proxyReq, req) => {
    alignDockerOrigin(proxyReq)

    if (dockerSessionCookie) {
      proxyReq.setHeader('Cookie', dockerSessionCookie)
    }
  }
  const sendJson = (res, statusCode, data) => {
    res.statusCode = statusCode
    res.setHeader('Content-Type', 'application/json; charset=utf-8')
    res.end(JSON.stringify(data))
  }
  const setBrowserIdentityCookie = (res, identityCookie, expiresAt) => {
    if (!identityCookie) return
    const expiresDate = expiresAt ? new Date(expiresAt) : null
    const expires = expiresDate && !Number.isNaN(expiresDate.getTime())
      ? `; Expires=${expiresDate.toUTCString()}`
      : ''
    res.setHeader(
      'Set-Cookie',
      `${identityCookie}; Path=/; HttpOnly; SameSite=Lax${expires}`
    )
  }
  return {
    plugins: [
      vue(),
      {
        name: 'docker-auth-login',
        configureServer(server) {
          server.middlewares.use('/docker-auth/login', async (req, res) => {
            if (req.method !== 'POST') {
              sendJson(res, 405, { message: 'Method Not Allowed' })
              return
            }

            try {
              const body = await parseJsonBody(req)
              const session = await loginDockerPlatform(body)
              dockerSessionCookie = session.cookie
              setBrowserIdentityCookie(res, session.identityCookie, session.expiresAt)
              sendJson(res, 200, { success: true, expiresAt: session.expiresAt })
            } catch (error) {
              sendJson(res, 500, { success: false, message: error.message || '原平台登录失败' })
            }
          })
        }
      }
    ],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      open: 'http://127.0.0.1:5173/login',
      proxy: {
        // SpringBoot backend
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, '')
        },

        // Docker service WebSocket endpoint.
        '/docker-api/common/notify': {
          target: 'ws://127.0.0.1:9920',
          changeOrigin: true,
          ws: true,
          configure: (proxy) => {
            proxy.on('proxyReqWs', (proxyReq, req) => {
              applyDockerHeaders(proxyReq, req)
              logDockerProxyRequest(proxyReq, req)
            })
          },
          rewrite: (path) => path.replace(/^\/docker-api/, '/api')
        },

        // Docker service HTTP endpoints.
        '/docker-api': {
          target: 'http://127.0.0.1:9920',
          changeOrigin: true,
          configure: (proxy) => {
            proxy.on('proxyReq', (proxyReq, req) => {
              applyDockerHeaders(proxyReq, req)
              logDockerProxyRequest(proxyReq, req)
            })
          },
          rewrite: (path) => path.replace(/^\/docker-api/, '/api')
        }
      }
    }
  }
})
