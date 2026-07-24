import type { GameCreated, GuessResult, Me, Round } from './types'

const BASE = import.meta.env.VITE_API_BASE ?? '/api/v1'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${BASE}${path}`, {
      // The anonymous session cookie must ride along on every call, or each
      // request becomes a different guest.
      credentials: 'include',
      headers: { 'Content-Type': 'application/json' },
      ...init,
    })
  } catch (cause) {
    throw new ApiError(0, `Network error calling ${path}: ${String(cause)}`)
  }
  if (!res.ok) {
    throw new ApiError(res.status, `${init?.method ?? 'GET'} ${path} -> ${res.status}`)
  }
  return (await res.json()) as T
}

export const api = {
  createGame: () => req<GameCreated>('/games', { method: 'POST' }),
  getRound: (gameId: string, index: number) => req<Round>(`/games/${gameId}/rounds/${index}`),
  guess: (gameId: string, index: number, lat: number, lon: number) =>
    req<GuessResult>(`/games/${gameId}/rounds/${index}/guess`, {
      method: 'POST',
      body: JSON.stringify({ lat, lon }),
    }),
  me: () => req<Me>('/me'),
}
