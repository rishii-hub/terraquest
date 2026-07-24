/** Five rounds, 5000 points each. */
export const MAX_TOTAL = 25_000

/** Human distance: metres below 1 km, else km with sensible precision. */
export function formatDistance(metres: number): string {
  if (metres < 1000) return `${Math.round(metres)} m`
  const km = metres / 1000
  return km < 10 ? `${km.toFixed(1)} km` : `${Math.round(km).toLocaleString()} km`
}

/** Thousands-separated score. */
export function formatScore(score: number): string {
  return Math.round(score).toLocaleString()
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value))
}
