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
        headers: {
          Cookie: 'ahksoil_identity_session=MTc4Mjk3NjM1M3w3eXFCNEdma3hiRE9Zc1BYc2NETkNnOVV3bzlEX2RCVmt6b0RvU1BDWFpZV1ZZQTRnWFFBSlZxdjBsMklibm1FRXg3UUNlWU5jS0hIN201N1FuczE5c2VnQTBGOEFuSG8wdEdDbTI2X3RsSTZ2MHFTSmcxOXRic0ljR1RNaU5NQVFnTTFCYWY4ZDFRR3B6bWxVWklQU18xREtVZXdzYWVhdDd5MnR6aW9WU2hrbUNGdkNkLS05c250LUxfQUp4WnhxbFdhMFQ4ekRsSEpTa0EzNFEtS3hPQTRadzNXcW9SS2liaGZmWHdyYmYtNkNsUm5laGdNVmJsY083R3Jpb1hGOGljTmFSMXRXdGlKMExkTVRuVDF8jfYBK8MH_WyTYRfMa8aj4P9fy2izDF4uDyZuXmd4BIQ=; csrf_token_1da6369563bef...=yeUP4RxHcMMqGBJMiBe0nkSp9+EvdopeYAT5ayQkCAo='
        },
        rewrite: (path) => path.replace(/^\/docker-api/, '/api')
      }
    }
  }
})

