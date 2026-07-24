import { useEffect, useRef, useState } from 'react'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import type { Feature, LineString } from 'geojson'
import type { LatLng } from '../game/useGame'

const STYLE = import.meta.env.VITE_MAP_STYLE ?? 'https://tiles.openfreemap.org/styles/liberty'
const LINE_SOURCE = 'guess-line'

interface Props {
  mode: 'guess' | 'result'
  pin: LatLng | null
  actual: LatLng | null
  onPick: (p: LatLng) => void
}

/**
 * The guess map. A single MapLibre instance is created once and kept mounted for
 * the whole game; only its container's size/position changes between the corner
 * "guess" overlay and the full-screen "result" view. This is deliberate -- a map
 * that unmounts and remounts each round leaks WebGL contexts until the browser
 * refuses to make more.
 */
export function GuessMap({ mode, pin, actual, onPick }: Props) {
  const containerRef = useRef<HTMLDivElement>(null)
  const mapRef = useRef<maplibregl.Map | null>(null)
  const guessMarker = useRef<maplibregl.Marker | null>(null)
  const actualMarker = useRef<maplibregl.Marker | null>(null)
  const [expanded, setExpanded] = useState(false)

  // Keep the latest onPick/mode without re-binding the map click handler.
  const onPickRef = useRef(onPick)
  onPickRef.current = onPick
  const modeRef = useRef(mode)
  modeRef.current = mode

  // Create the map once.
  useEffect(() => {
    if (!containerRef.current) return
    const map = new maplibregl.Map({
      container: containerRef.current,
      style: STYLE,
      center: [0, 20],
      zoom: 0.8,
      attributionControl: { compact: true },
      dragRotate: false,
    })
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'bottom-right')
    map.on('click', (e) => {
      if (modeRef.current !== 'guess') return
      onPickRef.current({ lat: e.lngLat.lat, lon: e.lngLat.lng })
    })
    mapRef.current = map
    return () => {
      map.remove()
      mapRef.current = null
      guessMarker.current = null
      actualMarker.current = null
    }
  }, [])

  // Reflect the guess pin.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    if (!pin) {
      guessMarker.current?.remove()
      guessMarker.current = null
      return
    }
    if (!guessMarker.current) {
      guessMarker.current = new maplibregl.Marker({ color: '#2563eb' })
        .setLngLat([pin.lon, pin.lat])
        .addTo(map)
    } else {
      guessMarker.current.setLngLat([pin.lon, pin.lat])
    }
  }, [pin])

  // On result, add the true-location marker and the line, and frame both points.
  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    if (mode === 'result' && actual) {
      if (!actualMarker.current) {
        actualMarker.current = new maplibregl.Marker({ color: '#16a34a' })
          .setLngLat([actual.lon, actual.lat])
          .addTo(map)
      } else {
        actualMarker.current.setLngLat([actual.lon, actual.lat])
      }
    } else {
      actualMarker.current?.remove()
      actualMarker.current = null
    }

    const coords = mode === 'result' && pin && actual
      ? [[pin.lon, pin.lat], [actual.lon, actual.lat]]
      : []
    const feature: Feature<LineString> = {
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: coords },
      properties: {},
    }
    const drawLine = () => {
      const existing = map.getSource(LINE_SOURCE) as maplibregl.GeoJSONSource | undefined
      if (existing) {
        existing.setData(feature)
      } else {
        map.addSource(LINE_SOURCE, { type: 'geojson', data: feature })
        map.addLayer({
          id: LINE_SOURCE,
          type: 'line',
          source: LINE_SOURCE,
          paint: { 'line-color': '#ef4444', 'line-width': 2, 'line-dasharray': [2, 2] },
        })
      }
    }
    if (map.isStyleLoaded()) drawLine()
    else map.once('load', drawLine)

    if (mode === 'result' && pin && actual) {
      const bounds = new maplibregl.LngLatBounds([pin.lon, pin.lat], [pin.lon, pin.lat]).extend([
        actual.lon,
        actual.lat,
      ])
      map.fitBounds(bounds, { padding: 96, maxZoom: 6, duration: 600 })
    }
  }, [mode, actual, pin])

  // MapLibre must be told when its container resizes (mode switch or hover expand).
  useEffect(() => {
    const map = mapRef.current
    if (!map) return
    const id = window.setTimeout(() => map.resize(), 220)
    return () => window.clearTimeout(id)
  }, [mode, expanded])

  const wrapperClass =
    mode === 'result'
      ? 'absolute inset-0'
      : `absolute bottom-4 right-4 z-10 overflow-hidden rounded-lg shadow-2xl ring-1 ring-white/25 transition-all duration-200 ${
          expanded ? 'h-[60vh] w-[min(38rem,70vw)]' : 'h-40 w-64'
        }`

  return (
    <div
      className={wrapperClass}
      onMouseEnter={() => mode === 'guess' && setExpanded(true)}
      onMouseLeave={() => mode === 'guess' && setExpanded(false)}
    >
      <div ref={containerRef} className="h-full w-full" />
    </div>
  )
}
