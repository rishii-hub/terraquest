import { useCallback, useReducer } from 'react'
import { api } from '../api/client'
import { resolveImageUrl } from '../lib/resolveImageUrl'
import type { GuessResult, Round } from '../api/types'

export interface LatLng {
  lat: number
  lon: number
}

export interface RoundRecord {
  index: number
  round: Round
  guess: LatLng
  result: GuessResult
}

export type Status = 'idle' | 'loading' | 'playing' | 'submitting' | 'result' | 'summary' | 'error'

interface State {
  status: Status
  gameId: string | null
  roundCount: number
  round: Round | null
  pin: LatLng | null
  result: GuessResult | null
  history: RoundRecord[]
  error: string | null
}

const INITIAL: State = {
  status: 'idle',
  gameId: null,
  roundCount: 5,
  round: null,
  pin: null,
  result: null,
  history: [],
  error: null,
}

type Action =
  | { t: 'reset' }
  | { t: 'loading' }
  | { t: 'gameStarted'; gameId: string; roundCount: number }
  | { t: 'roundLoaded'; round: Round }
  | { t: 'pin'; pin: LatLng }
  | { t: 'submitting' }
  | { t: 'result'; record: RoundRecord }
  | { t: 'summary' }
  | { t: 'error'; message: string }

function reducer(state: State, action: Action): State {
  switch (action.t) {
    case 'reset':
      return { ...INITIAL }
    case 'loading':
      return { ...state, status: 'loading', error: null }
    case 'gameStarted':
      return { ...state, gameId: action.gameId, roundCount: action.roundCount }
    case 'roundLoaded':
      return { ...state, status: 'playing', round: action.round, pin: null, result: null }
    case 'pin':
      // Pins are only accepted while actively guessing.
      return state.status === 'playing' ? { ...state, pin: action.pin } : state
    case 'submitting':
      return { ...state, status: 'submitting' }
    case 'result':
      return {
        ...state,
        status: 'result',
        result: action.record.result,
        history: [...state.history.filter((r) => r.index !== action.record.index), action.record],
      }
    case 'summary':
      return { ...state, status: 'summary' }
    case 'error':
      return { ...state, status: 'error', error: action.message }
  }
}

function message(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

export interface Game {
  state: State
  startGame: () => Promise<void>
  placePin: (pin: LatLng) => void
  submitGuess: () => Promise<void>
  next: () => Promise<void>
  playAgain: () => Promise<void>
  /** Re-mint the current round's signed URL (idempotent) and return a loadable src. */
  fetchFreshUrl: () => Promise<string>
}

export function useGame(): Game {
  const [state, dispatch] = useReducer(reducer, INITIAL)

  const loadRound = useCallback(async (gameId: string, index: number) => {
    dispatch({ t: 'loading' })
    try {
      const round = await api.getRound(gameId, index)
      dispatch({ t: 'roundLoaded', round })
    } catch (e) {
      dispatch({ t: 'error', message: message(e) })
    }
  }, [])

  const startGame = useCallback(async () => {
    dispatch({ t: 'loading' })
    try {
      const game = await api.createGame()
      dispatch({ t: 'gameStarted', gameId: game.gameId, roundCount: game.roundCount })
      await loadRound(game.gameId, 0)
    } catch (e) {
      dispatch({ t: 'error', message: message(e) })
    }
  }, [loadRound])

  const placePin = useCallback((pin: LatLng) => dispatch({ t: 'pin', pin }), [])

  const submitGuess = useCallback(async () => {
    const gameId = state.gameId
    const round = state.round
    const pin = state.pin
    if (!gameId || !round || !pin) return
    dispatch({ t: 'submitting' })
    try {
      const result = await api.guess(gameId, round.index, pin.lat, pin.lon)
      dispatch({ t: 'result', record: { index: round.index, round, guess: pin, result } })
    } catch (e) {
      dispatch({ t: 'error', message: message(e) })
    }
  }, [state.gameId, state.round, state.pin])

  const next = useCallback(async () => {
    if (!state.gameId || !state.round) return
    if (state.result?.gameComplete) {
      dispatch({ t: 'summary' })
      return
    }
    await loadRound(state.gameId, state.round.index + 1)
  }, [state.gameId, state.round, state.result, loadRound])

  const playAgain = useCallback(async () => {
    dispatch({ t: 'reset' })
    await startGame()
  }, [startGame])

  const fetchFreshUrl = useCallback(async () => {
    if (!state.gameId || !state.round) throw new Error('No active round')
    const round = await api.getRound(state.gameId, state.round.index)
    return resolveImageUrl(round.imageUrl)
  }, [state.gameId, state.round])

  return { state, startGame, placePin, submitGuess, next, playAgain, fetchFreshUrl }
}
