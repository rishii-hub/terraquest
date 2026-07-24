import { useRef, useState, type PointerEvent, type WheelEvent } from 'react'
import { useRoundImage } from '../game/useRoundImage'
import { clamp } from '../lib/format'
import { ReloadingOverlay } from './ReloadingOverlay'
import type { Round } from '../api/types'

interface Props {
  round: Round
  fetchFreshUrl: () => Promise<string>
}

/**
 * A flat perspective frame. Four in five rounds are flat, so this gets equal care
 * to the panorama: fitted to the viewport, wheel/pinch to zoom, drag to pan when
 * zoomed. No fake 3D panning -- a flat image has no more to show than it shows.
 */
export function FlatImageViewer({ round, fetchFreshUrl }: Props) {
  const { src, failed, onError } = useRoundImage(round, fetchFreshUrl)
  const [zoom, setZoom] = useState(1)
  const [offset, setOffset] = useState({ x: 0, y: 0 })
  const drag = useRef<{ x: number; y: number } | null>(null)

  const onWheel = (e: WheelEvent) => {
    const next = clamp(zoom - e.deltaY * 0.0015, 1, 5)
    setZoom(next)
    if (next === 1) setOffset({ x: 0, y: 0 })
  }

  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    if (zoom === 1) return
    drag.current = { x: e.clientX - offset.x, y: e.clientY - offset.y }
    e.currentTarget.setPointerCapture(e.pointerId)
  }
  const onPointerMove = (e: PointerEvent) => {
    if (!drag.current) return
    setOffset({ x: e.clientX - drag.current.x, y: e.clientY - drag.current.y })
  }
  const onPointerUp = () => {
    drag.current = null
  }

  return (
    <div
      className="absolute inset-0 flex touch-none select-none items-center justify-center overflow-hidden bg-neutral-900"
      onWheel={onWheel}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      style={{ cursor: zoom > 1 ? (drag.current ? 'grabbing' : 'grab') : 'default' }}
    >
      {failed ? (
        <ReloadingOverlay />
      ) : (
        <img
          src={src}
          alt="Where in the world is this?"
          onError={onError}
          draggable={false}
          className="max-h-full max-w-full will-change-transform"
          style={{ transform: `translate(${offset.x}px, ${offset.y}px) scale(${zoom})` }}
        />
      )}
    </div>
  )
}
