# ADR 0003: Which dependencies get abstracted

Status: Accepted (2026-07-22)

## Context

Requirement raised: treat every external dependency as replaceable, with
interfaces for imagery, location, storage, authentication and mapping.

Abstractions have a cost. Ones that never get a second implementation are
technical debt wearing the costume of architecture.

## Decision

Build:

- **ImageryProvider.** Highest value. Mapillary is Meta-owned and changed API
  terms in January 2026; coverage is the top product risk; KartaView and
  user-contributed imagery are real alternatives. The interface expresses
  search radius in metres, never degrees, so Mapillary's 0.01-degree bbox
  ceiling stays inside its adapter.
- **StorageProvider.** Thin. Justified by testability -- an in-memory
  implementation lets asset pipeline tests run without credentials -- not by
  migration, since R2 is S3-compatible and the AWS SDK is already portable.

Split, rather than build as specified:

- **"LocationProvider"** is two unrelated seams. `CandidateSeedSource`
  (where probe points come from) and `LocationSamplingStrategy` (how a round
  picks from the pool) vary independently and for different reasons. Merging
  them creates a type with two reasons to change.

Do not build:

- **AuthProvider.** Spring Security already defines `AuthenticationProvider`
  plus OAuth2 and OIDC support. A parallel abstraction means fighting the
  framework and reimplementing its security guarantees worse. The real seam is
  a discipline: domain code sees only a `UserId`. Enforced by ArchUnit.
- **MapProvider.** MapLibre is itself the abstraction -- an open standard over
  vector tiles. What actually varies is the tile source, which is a config URL.
  Wrapping MapLibre to allow a Leaflet migration that will not happen means
  surrendering the GPU rendering and custom layers MapLibre was chosen for.

## Consequences

Two SPIs to maintain, two seams where one was requested, two abstractions not
written. Reversible: if a second map or auth backend ever becomes real, the
interface can be extracted then, against a known second implementation, which
produces a better interface than guessing now.
