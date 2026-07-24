import { RoundViewer } from './RoundViewer'
import { GuessMap } from './GuessMap'
import { GuessControls } from './GuessControls'
import { ResultPanel } from './ResultPanel'
import type { Game } from '../game/useGame'

/**
 * The playing surface: image behind, guess map in front. The map is mounted here
 * for the whole game and merely resizes between the corner "guess" overlay and the
 * full-screen "result" view -- it is never remounted per round.
 */
export function GameLayout({ game }: { game: Game }) {
  const { state } = game
  const mode = state.status === 'result' ? 'result' : 'guess'
  const actual = state.result ? { lat: state.result.actualLat, lon: state.result.actualLon } : null

  return (
    <div className="relative h-dvh w-screen overflow-hidden bg-black">
      {mode === 'guess' && state.round && (
        <RoundViewer round={state.round} fetchFreshUrl={game.fetchFreshUrl} />
      )}

      <GuessMap mode={mode} pin={state.pin} actual={actual} onPick={game.placePin} />

      {mode === 'guess' && state.round && (
        <GuessControls
          round={state.round}
          hasPin={state.pin !== null}
          submitting={state.status === 'submitting'}
          onGuess={game.submitGuess}
        />
      )}

      {mode === 'result' && state.result && <ResultPanel result={state.result} onNext={game.next} />}

      {state.status === 'loading' && (
        <div className="absolute inset-0 z-30 flex items-center justify-center bg-black/60">
          <div className="h-10 w-10 animate-spin rounded-full border-2 border-neutral-700 border-t-emerald-400" />
        </div>
      )}
    </div>
  )
}
