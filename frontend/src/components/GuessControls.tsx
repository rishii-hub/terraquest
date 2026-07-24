import type { Round } from '../api/types'

interface Props {
  round: Round
  hasPin: boolean
  submitting: boolean
  onGuess: () => void
}

/** The round HUD: a projection hint and the Guess button (disabled with no pin). */
export function GuessControls({ round, hasPin, submitting, onGuess }: Props) {
  return (
    <div className="pointer-events-none absolute inset-x-0 top-0 z-20 flex items-start justify-between p-4">
      <span className="pointer-events-none rounded-full bg-black/50 px-3 py-1 text-xs font-medium text-white/80 backdrop-blur">
        {round.projection === 'EQUIRECTANGULAR' ? '360° — drag to look around' : 'Drag to pan · scroll to zoom'}
      </span>

      <div className="pointer-events-none fixed inset-x-0 bottom-6 flex justify-center">
        <button
          type="button"
          onClick={onGuess}
          disabled={!hasPin || submitting}
          className="pointer-events-auto rounded-full bg-emerald-500 px-8 py-3 text-base font-semibold text-white shadow-xl transition hover:bg-emerald-400 disabled:cursor-not-allowed disabled:bg-neutral-600 disabled:text-neutral-300"
        >
          {submitting ? 'Scoring…' : hasPin ? 'Guess' : 'Drop a pin on the map to guess'}
        </button>
      </div>
    </div>
  )
}
