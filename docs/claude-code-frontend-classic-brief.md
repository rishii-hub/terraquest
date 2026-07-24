# Domain brief — Classic Mode frontend

Branch: `feat/frontend-classic`. One PR, though it is large enough that stacked
commits inside it are welcome.

## Goal

Make TerraQuest playable. A person opens `localhost:5173`, sees a street-level
image, pans around it, clicks a world map to guess, and gets a score with the
true location revealed. Five rounds, then a total.

Nothing else. No accounts UI, no leaderboards, no daily challenge, no XP display,
no settings.

## What already exists

The backend is complete for this. Do not modify it.

    POST /api/v1/games                      -> { gameId, roundCount }
    GET  /api/v1/games/{id}/rounds/{n}      -> { roundId, index, imageUrl,
                                                 projection, width, height,
                                                 initialHeading, timeLimitSeconds }
    POST /api/v1/games/{id}/rounds/{n}/guess -> { score, distanceMetres,
                                                  actualLat, actualLon,
                                                  countryCode, attribution,
                                                  runningTotal, gameComplete }
    GET  /api/v1/me                         -> { id, username, xp }

Identity is an anonymous session cookie, minted on first request. The frontend
must send credentials on every call (`credentials: 'include'` in fetch, or the
axios equivalent) or each request becomes a different guest.

CORS already allows `http://localhost:5173`. CSRF is currently disabled for the
game chain; there is a TODO in `SecurityConfig` pointing at this PR. Do not
enable CSRF here — note in the PR body that it remains outstanding.

Round data deliberately contains no coordinates. Anything you need to draw the
answer arrives only in the guess response.

## Pool reality — this shapes the viewer

Measured from 5,116 harvested locations:

- **21% panoramic**, 79% flat perspective images
- 20 countries currently above the 15-panorama floor, likely 45-55 at full harvest
- Panoramas are concentrated: US alone is 23% of them

So the viewer needs **two modes**, driven by the `projection` field:

- `EQUIRECTANGULAR` — three.js sphere with the image as an inverted texture,
  drag to look around, scroll to zoom. Start facing `initialHeading`.
- `FLAT` — a plain fitted image. No fake panning. Zoom is fine.

Do not build only the panorama path and treat flat as a degraded case. Four in
five rounds are flat; that path deserves equal care.

## Stack

Vite + React + TypeScript, in a top-level `frontend/` directory. Tailwind for
styling. MapLibre GL JS for the guess map. three.js for the panorama viewer.

Basemap tiles: use a free source that needs no API key for local dev
(e.g. a public demo style or a Protomaps basemap). If a key is unavoidable,
read it from `.env` and document it — do not hardcode.

## Screens

**1. Round view**
Image fills the viewport. Guess map is a small overlay in a corner that expands
on hover or click, collapses again on leave. This is the GeoGuessr convention and
players expect it; do not invent a new layout.

Placing a pin enables a Guess button. No pin, no guess.

**2. Result view**
After guessing: the true location and the player's pin on a map, a line between
them, distance, and score. Attribution goes here — contributor name, licence,
link back to the source. This placement is deliberate: contributor names leak
the country, so they cannot appear over the image.

Then "Next round", or "See total" on round five.

**3. Summary**
Five rounds listed with per-round scores, the total out of 25,000, and a "Play
again" button.

## Things that will go wrong

- **Equirectangular textures need the sphere flipped**, or you are looking at the
  world from outside it. `geometry.scale(-1, 1, 1)`.
- **Signed URLs expire in 15 minutes.** A player who leaves a round open comes
  back to a broken image. Re-fetch the round on error rather than showing a
  broken image icon; the endpoint is idempotent and re-mints the URL.
- **MapLibre needs its CSS imported** or the map renders as an unstyled mess.
- **The map must not be inside a container that unmounts between rounds** without
  proper cleanup, or you leak WebGL contexts and the browser eventually refuses
  to create more.

## Done

- `npm run dev` serves a playable game against a locally running backend
- Both projection types render correctly
- A full five-round game can be completed
- Attribution appears on every result screen
- Works at 1920x1080 and on a phone-sized viewport
- README updated with frontend setup steps
- PR body includes a screenshot of a round and a result screen

## Out of scope

Accounts, leaderboards, daily challenge, XP, passport, multiplayer, sound,
animations beyond basic transitions, and any backend change.

---

## Implementation notes (added during planning)

- **Local imagery is `file://`.** `LocalFilesystemStorageProvider.signedUrl` returns a
  `file:///…/terraquest-imagery/img/<uuid>` URI, which a browser cannot load from an http
  origin, and the backend exposes no HTTP image endpoint. This is a real gap in the local
  storage provider — it can *store* images but cannot *serve* them to a browser. This PR
  bridges it **for dev only** with a Vite dev-server middleware that streams the local
  imagery directory. A self-hosted deployment needs either a backend image endpoint or R2
  presigned URLs; that is a **follow-up PR**, not this one.
