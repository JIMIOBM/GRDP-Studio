import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const dockerCookie = env.VITE_DOCKER_COOKIE

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      open: true,
      proxy: {
        // Independent read-only FlowBalance development backend.
        '/flowbalance': {
          target: 'http://127.0.0.1:8891',
          changeOrigin: true
        },
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
          headers: dockerCookie ? { Cookie: dockerCookie } : undefined,
          rewrite: (path) => path.replace(/^\/docker-api/, '/api')
        },

        // Docker service HTTP endpoints.
        '/docker-api': {
          target: 'http://127.0.0.1:9920',
          changeOrigin: true,
          headers: dockerCookie ? { Cookie: dockerCookie } : undefined,
          rewrite: (path) => path.replace(/^\/docker-api/, '/api')
        }
      }
    }
  }
})