## What changed

<!-- A concise summary of the change. -->

## Why

<!-- The motivation. Link any issue or ADR. What problem does this solve? -->

## How tested

<!-- Commands run, scenarios covered. e.g. `mvn verify`, manual steps, new tests. -->

## Checklist

- [ ] Subject follows Conventional Commits (imperative, < 72 chars, no trailing period)
- [ ] No secrets committed (`.env`, Mapillary token, R2 keys)
- [ ] No large or regenerable data files (`*.tsv`, GeoNames/Natural Earth dumps, `spike_results*.json`)
- [ ] `ArchitectureTest` still passes, or a rule change is called out and justified above
- [ ] Docs/ADRs updated if behaviour or a decision changed
