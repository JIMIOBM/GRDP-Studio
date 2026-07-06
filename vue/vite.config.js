import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";
import flowBalancePlugin from "./flowBalancePlugin.js";

const useLocalFlowPlugin = process.env.VITE_USE_LOCAL_FLOW_PLUGIN === "true";

export default defineConfig({
  plugins: [vue(), ...(useLocalFlowPlugin ? [flowBalancePlugin()] : [])],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: {
    port: 5173,
    open: true,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:9920",
        changeOrigin: true,
      },
      "/docker-api/common/notify": {
        target: "ws://127.0.0.1:9920",
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/docker-api/, "/api"),
      },
      "/docker-api": {
        target: "http://127.0.0.1:9920",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/docker-api/, "/api"),
      },
    },
  },
  clearScreen: false,
});
