# ADR 0004: The attribution seam

Status: Accepted (2026-07-22)

## Context

`GameService` reveals CC-BY-SA credit on the result screen by calling
`round.getAsset().attributionFor(location)`. That one call sits on a fault line
between three modules and two ArchUnit rules:

- `ImageAsset` lives in `imagery`.
- `Location` lives in `location`.
- The `Attribution` value it produces is consumed by `GuessResult` in `game`.

The module dependencies that already exist are fixed and point one way:
`game -> imagery` (GameService uses `ImageAsset`) and `location -> imagery`
(the harvester uses `ImageAssetService`). `no_cycles_between_feature_modules`
forbids any edge back. So two naive shapes are both illegal:

- `ImageAsset.attributionFor(Location)` taking `location.Location` directly
  creates `imagery -> location`, closing a cycle with `location -> imagery`.
- Returning a `game.Attribution` creates `imagery -> game`, closing a cycle with
  `game -> imagery`.

A further constraint: `ImageAsset` cannot reach the Mapillary adapter to build
provider-specific URLs. `provider_adapters_are_only_reachable_through_the_spi`
seals `imagery.mapillary..` behind the SPI, and `imagery` is outside it.

## Decision

Two moves, each dictated by the rule it satisfies.

- **`Attribution` lives in `shared`, not `imagery`.** It is produced in
  `imagery` and consumed in `game`; putting it in either makes the other depend
  back on it. It is a pure four-field value type, which is exactly what the
  leaf kernel is for. Placing it in `imagery` would have forced `game -> imagery`
  purely to name a DTO, and would have collided conceptually with the SPI's own
  richer `ImageryProvider.Attribution` (a different, provider-facing shape).

- **`ImageAsset.attributionFor` accepts an `imagery.AttributionSource`**, an
  interface `Location` implements, rather than `Location` itself. The asset
  depends on an abstraction in its own module; `Location` depends *inward* on
  `imagery` (a direction that already exists), so no cycle forms. The asset
  merely delegates: `return source.sourceAttribution();`.

The URL formatting itself lives in `Location.sourceAttribution()`. This is
deliberate, not incidental: the schema stores Mapillary-shaped identifiers
(`mapillary_image_id`, `creator_id`) and has no `provider` column and nowhere to
persist provider-neutral attribution URLs. The knowledge is Mapillary-specific
because the data is. Confining it to one method on the entity that already owns
those columns means `ImageAsset` stays provider-neutral, and the day the schema
grows a `provider` column (see below), only `Location.sourceAttribution()`
changes.

## Consequences

- One new interface (`AttributionSource`) and one value type relocated. No
  module cycle; ArchitectureTest passes unmodified.
- `ImageAsset.attributionFor` is a thin delegator. That is the point -- the asset
  carries no attribution data of its own, so it has nothing to add.
- The Mapillary-shaped schema is the real coupling. Generalising it
  (`provider` column, per-provider URL templates) is a follow-up migration, at
  which point the formatting can move behind the SPI and out of the entity. The
  seam is placed so that change is local.
