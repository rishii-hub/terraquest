interface LoadingProps {
  label: string
}

export function LoadingScreen({ label }: LoadingProps) {
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center gap-4 bg-neutral-950">
      <div className="h-10 w-10 animate-spin rounded-full border-2 border-neutral-700 border-t-emerald-400" />
      <p className="text-sm text-neutral-400">{label}</p>
    </div>
  )
}

interface ErrorProps {
  message: string | null
  onRetry: () => void
}

export function ErrorScreen({ message, onRetry }: ErrorProps) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-neutral-950 p-6">
      <div className="w-full max-w-sm rounded-2xl bg-neutral-900 p-6 text-center shadow-2xl ring-1 ring-white/10">
        <h1 className="text-lg font-semibold text-white">Something went wrong</h1>
        <p className="mt-2 break-words text-sm text-neutral-400">
          {message ?? 'Unknown error.'} Is the backend running on :8080?
        </p>
        <button
          type="button"
          onClick={onRetry}
          className="mt-5 w-full rounded-lg bg-emerald-500 py-2.5 font-semibold text-white transition hover:bg-emerald-400"
        >
          Try again
        </button>
      </div>
    </div>
  )
}
