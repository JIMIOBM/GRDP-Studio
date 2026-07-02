import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
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
      // SpringBoot 后端
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '')
      },

      // ✅ WebSocket 专用代理（必须放在 /docker-api 之前，target 用 ws://）
      '/docker-api/common/notify': {
        target: 'ws://127.0.0.1:9920',
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/docker-api/, '/api')
      },

      // Docker 服务 HTTP 接口
      '/docker-api': {
        target: 'http://127.0.0.1:9920',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/docker-api/, '/api')
      }
    }
  }
})

