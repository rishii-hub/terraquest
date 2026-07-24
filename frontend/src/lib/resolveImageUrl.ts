/**
 * Turn a backend image URL into one the browser can actually load.
 *
 * In local dev the backend's LocalFilesystemStorageProvider returns a
 * `file:///…/img/<uuid>` URL, which a browser cannot load from an http page. The
 * Vite dev middleware (see vite.config.ts) serves those same files under `/__img`,
 * so rewrite `file:` URLs to that path. Production presigned `https` URLs pass
 * through unchanged.
 */
export function resolveImageUrl(raw: string): string {
  if (!raw.startsWith('file:')) return raw
  const match = /\/(img\/[0-9a-fA-F-]+)$/.exec(raw)
  return match ? `/__img/${match[1]}` : raw
}
