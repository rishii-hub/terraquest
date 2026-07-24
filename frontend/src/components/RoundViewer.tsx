import { FlatImageViewer } from './FlatImageViewer'
import { PanoramaViewer } from './PanoramaViewer'
import type { Round } from '../api/types'

interface Props {
  round: Round
  fetchFreshUrl: () => Promise<string>
}

/** Picks the viewer for the round's projection. */
export function RoundViewer({ round, fetchFreshUrl }: Props) {
  return round.projection === 'EQUIRECTANGULAR' ? (
    <PanoramaViewer round={round} fetchFreshUrl={fetchFreshUrl} />
  ) : (
    <FlatImageViewer round={round} fetchFreshUrl={fetchFreshUrl} />
  )
}
