/** Shown briefly when an image failed and we are re-minting its signed URL. */
export function ReloadingOverlay() {
  return (
    <div className="absolute inset-0 flex items-center justify-center bg-neutral-900 text-neutral-400">
      <span className="animate-pulse text-sm">Reloading image…</span>
    </div>
  )
}
