/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dashboard calls the Spring Boot backend through relative /api URLs.
// In development Vite proxies /api to BACKEND_URL (same variable the
// Streamlit dashboard and docker-compose use); in Docker nginx does the same.
// Set VITE_API_BASE_URL only to call the backend directly instead (the
// backend allows CORS from http://localhost:3000).
const backendUrl = process.env.BACKEND_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    strictPort: true,
    proxy: {
      '/api': { target: backendUrl, changeOrigin: true },
    },
  },
  preview: {
    port: 3000,
    proxy: {
      '/api': { target: backendUrl, changeOrigin: true },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        // Keep the graph library and React in their own long-cached chunks.
        manualChunks: {
          cytoscape: ['cytoscape'],
          react: ['react', 'react-dom'],
        },
      },
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
