# Contributing to TerraQuest

Thanks for your interest in TerraQuest. This document covers how we branch,
commit, and run the project locally.

## Branching

We use a trunk-based workflow. `main` is always releasable and is protected —
all changes land through a pull request.

- Branch off `main` and keep branches short-lived.
- Name branches by type:

  | Prefix      | For                                      |
  |-------------|------------------------------------------|
  | `feat/`     | new functionality                        |
  | `fix/`      | bug fixes                                 |
  | `chore/`    | tooling, deps, housekeeping              |
  | `docs/`     | documentation only                       |
  | `refactor/` | behaviour-preserving code changes        |

- PRs are **squash-merged** into `main`. The head branch is deleted automatically.
- There is no `develop` branch.

## Commit messages

We follow [Conventional Commits](https://www.conventionalcommits.org/).

- Format the subject as `type(scope): summary`, e.g. `feat(location): add quota sampler`.
- Keep the subject under 72 characters, in the imperative mood, with no trailing period.
- Explain **why** in the body when it is not obvious from the diff.

Because PRs are squash-merged, the PR title becomes the commit subject on
`main` — give it the same care.

## Running locally

Requirements: JDK 21, Docker, and a Mapillary developer token.

```bash
# 1. Start Postgres+PostGIS and Redis
docker compose up -d

# 2. Configure secrets
cp .env.example .env
# edit .env and set MAPILLARY_ACCESS_TOKEN (and R2 credentials if needed)

# 3. Build and run the full test suite
cd backend
mvn verify
```

`mvn verify` runs the architecture rules, unit tests, and the Testcontainers
integration tests (which spin up their own PostGIS), so Docker must be running.

## Architecture rules are decisions, not obstacles

`ArchitectureTest` encodes deliberate boundary decisions as ArchUnit rules — for
example, the game core must not depend on a concrete imagery provider. **A failing
rule is an architecture discussion, not a test to edit.** If a rule blocks you,
open an issue or raise it in your PR rather than relaxing the rule to make the
build pass. Changing a rule should be a conscious, reviewed decision.

## Before you open a PR

- Fill in the PR template (what changed, why, how tested).
- Confirm no secrets (`.env`, tokens, R2 keys) or large data files
  (`*.tsv`, GeoNames/Natural Earth dumps, `spike_results*.json`) are included.
