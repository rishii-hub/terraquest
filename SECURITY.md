# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities **privately** — do not open a public
issue, pull request, or discussion for them.

Use GitHub's private vulnerability reporting:

1. Go to the [Security tab](https://github.com/rishii-hub/terraquest/security) of the repository.
2. Click **Report a vulnerability**.
3. Describe the issue, including steps to reproduce and the impact.

This creates a private advisory visible only to you and the maintainers.

Please include, where you can:

- The affected component or endpoint.
- Steps to reproduce or a proof of concept.
- The impact you believe it has.

### What to expect

- We aim to acknowledge a report within a few days.
- We will work with you on a fix and coordinate disclosure once a patch is available.
- Please give us reasonable time to address the issue before any public disclosure.

## Scope

TerraQuest is pre-alpha and not yet deployed for public play. Reports about the
backend, build pipeline, and dependency handling are all in scope. Note that
Mapillary imagery is served under CC-BY-SA and its handling of third-party data
is governed by Mapillary's own policies.

## Secrets

Never include real credentials (Mapillary tokens, R2 keys, database passwords)
in a report or a reproduction. Redact them.
