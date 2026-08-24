import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Same-origin path as the Nginx container mode: the browser only ever
      // talks to the Vite dev server, which forwards /api/v1 to the locally
      // run Spring Boot backend (8080 by default, see application-local.yml).
      '/api/v1': {
        target: `http://localhost:${process.env.BACKEND_PORT ?? '8080'}`,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
