/// <reference types="vitest/config" />
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// En desarrollo el navegador habla solo con Vite (mismo origen) y Vite reenvía
// /api y /ws al API Gateway local, igual que hará nginx en producción.
const GATEWAY = 'http://localhost:8080'

const proxy = {
  '/api': { target: GATEWAY, changeOrigin: true },
  '/ws': { target: GATEWAY, ws: true, changeOrigin: true },
}

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5173, strictPort: true, proxy },
  preview: { port: 4173, strictPort: true, proxy },
  build: { target: 'es2022', sourcemap: false },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
    unstubGlobals: true,
  },
})
