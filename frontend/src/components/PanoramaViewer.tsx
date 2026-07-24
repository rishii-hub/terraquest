import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { resolveImageUrl } from '../lib/resolveImageUrl'
import { clamp } from '../lib/format'
import { ReloadingOverlay } from './ReloadingOverlay'
import type { Round } from '../api/types'

interface Props {
  round: Round
  fetchFreshUrl: () => Promise<string>
}

interface Scene {
  renderer: THREE.WebGLRenderer
  scene: THREE.Scene
  camera: THREE.PerspectiveCamera
  material: THREE.MeshBasicMaterial
  texture: THREE.Texture | null
  lon: number
  lat: number
  fov: number
  raf: number
}

/**
 * Equirectangular panorama on a three.js sphere. The sphere is scaled -1 on X so we
 * view it from the inside; the camera sits at the centre and we rotate its look
 * direction. One renderer is created per mount and reused across rounds (the texture
 * is swapped), and everything is disposed on unmount so we never leak WebGL contexts.
 */
export function PanoramaViewer({ round, fetchFreshUrl }: Props) {
  const mountRef = useRef<HTMLDivElement>(null)
  const sceneRef = useRef<Scene | null>(null)
  const [failed, setFailed] = useState(false)

  // Build the renderer/scene once.
  useEffect(() => {
    const el = mountRef.current
    if (!el) return

    const renderer = new THREE.WebGLRenderer({ antialias: true })
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
    renderer.setSize(el.clientWidth, el.clientHeight)
    el.appendChild(renderer.domElement)

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(75, el.clientWidth / el.clientHeight, 0.1, 1100)

    const geometry = new THREE.SphereGeometry(500, 60, 40)
    geometry.scale(-1, 1, 1) // view from inside the sphere
    const material = new THREE.MeshBasicMaterial({ color: 0x111111 })
    scene.add(new THREE.Mesh(geometry, material))

    const st: Scene = { renderer, scene, camera, material, texture: null, lon: 0, lat: 0, fov: 75, raf: 0 }
    sceneRef.current = st

    let dragging = false
    let px = 0
    let py = 0
    const onDown = (e: PointerEvent) => {
      dragging = true
      px = e.clientX
      py = e.clientY
    }
    const onMove = (e: PointerEvent) => {
      if (!dragging) return
      st.lon -= (e.clientX - px) * 0.15
      st.lat = clamp(st.lat + (e.clientY - py) * 0.15, -85, 85)
      px = e.clientX
      py = e.clientY
    }
    const onUp = () => {
      dragging = false
    }
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      st.fov = clamp(st.fov + e.deltaY * 0.05, 30, 90)
      camera.fov = st.fov
      camera.updateProjectionMatrix()
    }
    const onResize = () => {
      renderer.setSize(el.clientWidth, el.clientHeight)
      camera.aspect = el.clientWidth / el.clientHeight
      camera.updateProjectionMatrix()
    }
    el.addEventListener('pointerdown', onDown)
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    el.addEventListener('wheel', onWheel, { passive: false })
    window.addEventListener('resize', onResize)

    const target = new THREE.Vector3()
    const animate = () => {
      st.raf = requestAnimationFrame(animate)
      const phi = THREE.MathUtils.degToRad(90 - st.lat)
      const theta = THREE.MathUtils.degToRad(st.lon)
      target.set(
        500 * Math.sin(phi) * Math.cos(theta),
        500 * Math.cos(phi),
        500 * Math.sin(phi) * Math.sin(theta),
      )
      camera.lookAt(target)
      renderer.render(scene, camera)
    }
    animate()

    return () => {
      cancelAnimationFrame(st.raf)
      el.removeEventListener('pointerdown', onDown)
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      el.removeEventListener('wheel', onWheel)
      window.removeEventListener('resize', onResize)
      st.texture?.dispose()
      geometry.dispose()
      material.dispose()
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
      sceneRef.current = null
    }
  }, [])

  // Load (or swap) the texture whenever the round changes.
  useEffect(() => {
    const st = sceneRef.current
    if (!st) return
    let cancelled = false
    setFailed(false)
    st.lon = round.initialHeading ?? 0
    st.lat = 0
    st.fov = 75
    st.camera.fov = 75
    st.camera.updateProjectionMatrix()

    const apply = (texture: THREE.Texture) => {
      if (cancelled) {
        texture.dispose()
        return
      }
      texture.colorSpace = THREE.SRGBColorSpace
      st.texture?.dispose()
      st.material.map = texture
      st.material.color.set(0xffffff)
      st.material.needsUpdate = true
      st.texture = texture
    }

    const load = (url: string, allowRetry: boolean) => {
      new THREE.TextureLoader().load(url, apply, undefined, () => {
        if (cancelled) return
        if (allowRetry) {
          fetchFreshUrl()
            .then((fresh) => !cancelled && load(fresh, false))
            .catch(() => !cancelled && setFailed(true))
        } else {
          setFailed(true)
        }
      })
    }
    load(resolveImageUrl(round.imageUrl), true)

    return () => {
      cancelled = true
    }
  }, [round.roundId, round.imageUrl, round.initialHeading, fetchFreshUrl])

  return (
    <div ref={mountRef} className="absolute inset-0 touch-none bg-black">
      {failed && <ReloadingOverlay />}
    </div>
  )
}
