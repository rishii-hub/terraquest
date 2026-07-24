/// <reference types="node" />
import { defineConfig, loadEnv, type Plugin, type ViteDevServer, type PreviewServer } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

/**
 * Dev-only bridge for locally-stored imagery.
 *
 * The backend's LocalFilesystemStorageProvider can *store* images but only hands
 * out `file://` URLs, which a browser cannot load from an http origin, and it
 * exposes no HTTP image endpoint. This middleware serves the same files over http
 * so the game is playable in local dev. It is NOT a production surface -- a
 * self-hosted deployment needs a real backend image endpoint or R2 presigned URLs.
 */
function localImagery(dir: string): Plugin {
  const KEY = /^\/__img\/(img\/[0-9a-fA-F-]+)$/
  const attach = (server: ViteDevServer | PreviewServer) => {
    server.middlewares.use((req, res, next) => {
      const url = req.url?.split('?')[0] ?? ''
      const match = KEY.exec(url)
      if (!match) return next()
      const file = path.resolve(dir, match[1])
      if (!file.startsWith(path.resolve(dir))) {
        res.statusCode = 400
        return res.end('bad key')
      }
      fs.readFile(file, (err, data) => {
        if (err) {
          res.statusCode = 404
          return res.end('not found')
        }
        res.setHeader('Content-Type', 'image/jpeg')
        res.setHeader('Cache-Control', 'no-store')
        res.end(data)
      })
    })
  }
  return { name: 'terraquest-local-imagery', configureServer: attach, configurePreviewServer: attach }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // Match the backend default: ${java.io.tmpdir}/terraquest-imagery. Override with
  // LOCAL_STORAGE_DIR (the same env var the backend reads) if you relocated it.
  const imageryDir =
    process.env.LOCAL_STORAGE_DIR || env.LOCAL_STORAGE_DIR || path.join(os.tmpdir(), 'terraquest-imagery')
  const apiTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [react(), tailwindcss(), localImagery(imageryDir)],
    server: {
      port: 5173,
      // Proxy the API so the SPA is same-origin with the backend in dev: the guest
      // session cookie is then first-party and there are no CORS surprises.
      proxy: {
        '/api': { target: apiTarget, changeOrigin: false },
      },
    },
  }
})
