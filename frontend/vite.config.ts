import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  // Subpath the app is served from. Defaults to '/' (root); override with VITE_BASE
  // (trailing-slashed) to serve under a prefix — it drives asset URLs, the router basename,
  // and the API base.
  base: process.env.VITE_BASE || '/',
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
