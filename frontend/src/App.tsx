import { useEffect, useRef } from 'react'
import { useGame } from './game/useGame'
import { GameLayout } from './components/GameLayout'
import { SummaryScreen } from './components/SummaryScreen'
import { ErrorScreen, LoadingScreen } from './components/StatusScreens'

export default function App() {
  const game = useGame()
  const { status, gameId, round } = game.state
  const { startGame } = game

  // Auto-start once on load, so opening the page drops you straight into a round.
  // playAgain drives subsequent games explicitly, so this must not re-fire on idle.
  const started = useRef(false)
  useEffect(() => {
    if (!started.current) {
      started.current = true
      void startGame()
    }
  }, [startGame])

  if (status === 'summary') {
    return <SummaryScreen history={game.state.history} onPlayAgain={game.playAgain} />
  }
  if (status === 'error') {
    return <ErrorScreen message={game.state.error} onRetry={game.startGame} />
  }
  if (!gameId || !round) {
    return <LoadingScreen label="Starting game…" />
  }
  return <GameLayout game={game} />
}
