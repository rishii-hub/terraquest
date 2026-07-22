# ADR 0002: Proxy imagery instead of using MapillaryJS

Status: Accepted (2026-07-22)

## Context

A Mapillary image ID resolves to exact coordinates through a single public API
call. This repository is open source, so any endpoint we call is public
knowledge. Shipping the ID to the client makes cheating a one-line userscript.

MapillaryJS, the obvious viewer choice, requires the client to talk to Mapillary
directly with the real image ID.

## Decision

Proxy the imagery. The harvester downloads each image, strips metadata,
re-hosts it in R2 under a random opaque key, and serves it via short-lived
signed URLs. Coordinates never leave the server until a guess is committed.

We build our own equirectangular viewer rather than using MapillaryJS.

## Consequences

Accepted costs:
- A custom three.js viewer, roughly 1-2 weeks.
- Sequence-based movement is lost initially and must be rebuilt by
  pre-fetching adjacent frames server-side.
- Storage and egress, mitigated by R2 charging no egress fees.

Gained:
- Ranked play, leaderboards and Battle Royale become meaningful. Without this
  the entire competitive half of the product is unshippable.
- Provider independence: clients consume our asset URLs, so swapping or adding
  an imagery source changes nothing client-side.

## What this does not solve

Perceptual hashing still works. An attacker who plays enough rounds can hash
images and build a lookup table. This is true of every fixed-pool geography
game including GeoGuessr, and the only real mitigation is pool size. We are
raising the cost of cheating, not eliminating it. This must be stated plainly
in user-facing documentation rather than claiming the game is cheat-proof.
