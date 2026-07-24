# Domain brief — Dev-user auth (unblock Classic Mode)

Branch: `feat/dev-auth`. One PR.

## Why

`GameController` takes an `@AuthenticationPrincipal AuthenticatedUser`, but nothing
in the app produces one. Every game endpoint is therefore uncallable — the backend
can harvest and store locations but cannot start a round. This PR is the smallest
change that makes the game playable end to end.

The pool is ready: 5,116 locations, ~1,075 panoramic, 20 countries above the pano
floor.

## Explicitly NOT this PR

No OAuth, no JWT, no registration, no login screen, no password reset. Those are
Phase 3. Building them now delays the first playable round for machinery nobody
has used yet. If you find yourself adding a token library, stop.

## What to build

### 1. Anonymous session identity

A player gets an identity without signing up. On first request without a session,
create an `app_user` row with a generated username (e.g. `guest-a4f2c1`) and no
email, and bind it to the HTTP session.

Rationale: `game.user_id` is a NOT NULL FK to `app_user`, so a round needs a real
row. Anonymous-but-persistent means match history and stats work immediately, and
a future OAuth PR can claim an existing guest account rather than migrating data.

Requirements:
- `AuthenticatedUser` resolves for every request to `/api/v1/games/**`
- Same browser session yields the same `user_id` across rounds
- `email` and `oauth_provider` stay null; the schema already permits this
- No credential of any kind is involved

### 2. Security configuration

Extend the existing `SecurityConfig` chain — do not replace it.

- `/api/v1/admin/**` keeps HTTP Basic and `ROLE_ADMIN`, unchanged
- `/api/v1/games/**` permits the anonymous session identity
- CSRF: the game endpoints are called by a browser SPA, so either enable CSRF with
  a cookie-based token repository and document how the frontend sends it, or
  disable it for `/api/v1/games/**` and say plainly in the PR why that is
  acceptable for now. Pick one and justify it; do not leave it ambiguous.
- CORS: the frontend will run on a different port in dev (Vite, likely 5173).
  Allow that origin from configuration, not a hardcoded literal.

### 3. A "who am I" endpoint

`GET /api/v1/me` returning the current user's id, username, and XP. The frontend
needs this to show who it is playing as and to confirm the session survived a
refresh.

### 4. Tests

- The same session hits `/api/v1/games` twice and gets the same `user_id`
- A different session gets a different `user_id`
- An anonymous session cannot reach `/api/v1/admin/**`
- `ArchitectureTest` passes unmodified — Spring Security types must stay out of
  `game`, `location`, `progression` and `scoring`

## Manual verification to include in the PR description

With the app running, this sequence must work end to end using only curl:

    POST /api/v1/games                      -> gameId
    GET  /api/v1/games/{id}/rounds/0        -> image URL, no coordinates
    POST /api/v1/games/{id}/rounds/0/guess  -> score, distance, true location

Confirm explicitly that the round response contains **no** latitude, longitude,
country code, or Mapillary identifier. That is the anti-cheat contract from
ADR 0002 and this is the first time it can actually be exercised.

## Done

- `mvn verify` green, both CI checks pass
- The curl sequence above works against a running app
- Round response leaks nothing about location
- Admin endpoints unchanged
- PR description states the CSRF decision and its reasoning
