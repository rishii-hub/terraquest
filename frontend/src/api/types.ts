/** Mirrors the backend {@code Projection} enum. */
export type Projection = 'EQUIRECTANGULAR' | 'FLAT'

/** Response to POST /api/v1/games. */
export interface GameCreated {
  gameId: string
  roundCount: number
}

/**
 * A round to play. Deliberately carries no coordinates -- the answer arrives only
 * in {@link GuessResult}. {@code initialHeading} is a compass bearing in degrees.
 */
export interface Round {
  roundId: string
  index: number
  imageUrl: string
  projection: Projection
  width: number
  height: number
  initialHeading: number | null
  timeLimitSeconds: number | null
}

/** CC-BY-SA attribution, shown only on the result screen. */
export interface Attribution {
  contributor: string
  profileUrl: string | null
  licence: string
  sourceUrl: string
}

/** Response to POST .../guess: the score and the revealed truth. */
export interface GuessResult {
  score: number
  distanceMetres: number
  actualLat: number
  actualLon: number
  countryCode: string
  attribution: Attribution
  runningTotal: number
  gameComplete: boolean
}

/** Response to GET /api/v1/me. */
export interface Me {
  id: string
  username: string
  xp: number
}
