import { Attribution } from './Attribution'
import { formatDistance, formatScore } from '../lib/format'
import type { GuessResult } from '../api/types'

interface Props {
  result: GuessResult
  onNext: () => void
}

/** The result HUD over the full-screen map: score, distance, attribution, advance. */
export function ResultPanel({ result, onNext }: Props) {
  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-20 flex justify-center p-4">
      <div className="pointer-events-auto w-full max-w-xl rounded-xl bg-neutral-900/90 p-4 shadow-2xl ring-1 ring-white/10 backdrop-blur">
        <div className="flex items-center justify-between gap-4">
          <div>
            <div className="text-2xl font-bold text-white">
              {formatScore(result.score)}
              <span className="ml-1 text-sm font-normal text-neutral-400">/ 5,000</span>
            </div>
            <div className="text-sm text-neutral-300">
              {formatDistance(result.distanceMetres)} away · {result.countryCode}
            </div>
          </div>
          <button
            type="button"
            onClick={onNext}
            className="rounded-lg bg-emerald-500 px-5 py-2.5 font-semibold text-white transition hover:bg-emerald-400"
          >
            {result.gameComplete ? 'See total' : 'Next round'}
          </button>
        </div>
        <Attribution data={result.attribution} />
      </div>
    </div>
  )
}
