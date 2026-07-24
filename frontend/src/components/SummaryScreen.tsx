import { MAX_TOTAL, formatDistance, formatScore } from '../lib/format'
import type { RoundRecord } from '../game/useGame'

interface Props {
  history: RoundRecord[]
  onPlayAgain: () => void
}

/** End of game: per-round scores, the total out of 25,000, and Play again. */
export function SummaryScreen({ history, onPlayAgain }: Props) {
  const ordered = [...history].sort((a, b) => a.index - b.index)
  const total = ordered.reduce((sum, r) => sum + r.result.score, 0)

  return (
    <div className="flex min-h-dvh items-center justify-center bg-neutral-950 p-6">
      <div className="w-full max-w-md rounded-2xl bg-neutral-900 p-6 shadow-2xl ring-1 ring-white/10">
        <h1 className="text-2xl font-bold text-white">Game complete</h1>

        <ul className="mt-4 divide-y divide-white/10">
          {ordered.map((r) => (
            <li key={r.index} className="flex items-baseline justify-between py-2">
              <span className="text-neutral-300">Round {r.index + 1}</span>
              <span className="font-medium text-white">
                {formatScore(r.result.score)}
                <span className="ml-2 text-sm font-normal text-neutral-500">
                  {formatDistance(r.result.distanceMetres)}
                </span>
              </span>
            </li>
          ))}
        </ul>

        <div className="mt-4 flex items-baseline justify-between border-t border-white/10 pt-4">
          <span className="text-lg text-neutral-200">Total</span>
          <span className="text-3xl font-bold text-white">
            {formatScore(total)}
            <span className="ml-1 text-base font-normal text-neutral-400">/ {formatScore(MAX_TOTAL)}</span>
          </span>
        </div>

        <button
          type="button"
          onClick={onPlayAgain}
          className="mt-6 w-full rounded-lg bg-emerald-500 py-3 font-semibold text-white transition hover:bg-emerald-400"
        >
          Play again
        </button>
      </div>
    </div>
  )
}
