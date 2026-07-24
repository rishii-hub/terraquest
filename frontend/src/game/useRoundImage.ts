import { useCallback, useEffect, useRef, useState } from 'react'
import { resolveImageUrl } from '../lib/resolveImageUrl'
import type { Round } from '../api/types'

/**
 * Resolves a round's image to a loadable src and, once, re-mints an expired signed
 * URL when it fails to load (they last 15 minutes; the round endpoint is idempotent
 * and re-mints on re-fetch). Callers wire {@code onError} to the image/texture.
 */
export function useRoundImage(round: Round, fetchFreshUrl: () => Promise<string>) {
  const [src, setSrc] = useState(() => resolveImageUrl(round.imageUrl))
  const [failed, setFailed] = useState(false)
  const retried = useRef(false)

  useEffect(() => {
    setSrc(resolveImageUrl(round.imageUrl))
    setFailed(false)
    retried.current = false
  }, [round.roundId, round.imageUrl])

  const onError = useCallback(async () => {
    if (retried.current) {
      setFailed(true)
      return
    }
    retried.current = true
    try {
      setSrc(await fetchFreshUrl())
    } catch {
      setFailed(true)
    }
  }, [fetchFreshUrl])

  return { src, failed, onError }
}
