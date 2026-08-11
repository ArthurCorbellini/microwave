# CI Pipeline (test gate) — Design

**Date:** 2026-08-11
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 1.1 — Continuous Integration (test gate)

## Purpose

Add a GitHub Actions pipeline that runs each service's test suite (unit + Testcontainers integration tests) on every pull request, and use a branch protection rule on `main` to block merging when any service's tests fail.

## Motivation

Phase 1 was merged into `main` via a 10-commit PR with no automated verification — the only gate was manual review. This closes that gap. The check is independent of containerization (GitHub-hosted runners already have Docker available for Testcontainers), so it doesn't need to wait for Phase 2; doing it now protects every PR from here on, including Phase 2's own.

## Scope

- One GitHub Actions workflow, triggered on `pull_request` (targeting `main`) and `push` (to `main`).
- Runs `mvn -B verify` for each of the three services (`catalog`, `orders`, `payments`) — `verify` is required, not `test`, because `maven-failsafe-plugin` binds the Testcontainers `*IT.java` tests to the `integration-test`/`verify` goals, not `test`.
- A branch protection rule on `main` requiring all three resulting status checks to pass before a PR can be merged.

Out of scope (explicitly deferred, not part of this design):
- Building/publishing Docker images (belongs to Phase 2, once Dockerfiles exist).
- Linting/static analysis (no such tooling exists in the project yet — not introduced here).
- Path-based filtering to skip unaffected services on partial changes (YAGNI at 3 services; revisit if the matrix grows large enough that always running all of it becomes slow or costly).

## Design

### Workflow structure

A single job templated over a matrix of the three service names, run in parallel:

```yaml
name: CI

on:
  pull_request:
    branches: [main]
  push:
    branches: [main]

jobs:
  test:
    name: test (${{ matrix.service }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        service: [catalog, orders, payments]

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
          cache-dependency-path: services/${{ matrix.service }}/pom.xml

      - name: Run tests
        working-directory: services/${{ matrix.service }}
        run: mvn -B verify
```

Rejected alternatives:
- **Three explicit jobs** (one per service, steps duplicated) — every new service added in a later phase (`inventory`, `notifications` in Phase 3) would require copy-pasting a whole job block, which fights the consistency `docs/conventions.md` already establishes across services.
- **Single job, shell loop over services** — simplest YAML, but loses per-service parallelism and per-service pass/fail visibility in the PR checks UI (one generic check instead of three).

`fail-fast: false` is set deliberately: if one service's tests fail, the other two still run to completion, so a PR shows the full picture (which service(s) broke) rather than stopping at the first failure.

### Branch protection

Configured on the `main` branch (via `gh api`, since there's no interactive access to the GitHub UI in this environment): require the three status checks (`test (catalog)`, `test (orders)`, `test (payments)`) to pass before a PR can be merged.

### Roadmap update

Add a new entry to `docs/roadmap.md`, positioned between Phase 1 and Phase 2:

> ### Phase 1.1 — Continuous Integration (test gate)
>
> GitHub Actions workflow running each service's test suite (unit + Testcontainers integration tests) on every PR, gating merges to `main` via a required branch protection rule.
>
> Focus: closing the gap where PRs could merge without any automated verification.
>
> **Done when:** a PR with a failing test in any of the 3 services cannot be merged into `main`, and a PR with all tests passing can.

## Testing

This feature's own "test" is behavioral, not a Maven test suite:
- A PR introducing a deliberately broken test in one service shows a failing check for that service and is blocked from merging.
- A PR with all tests passing shows all three checks green and is mergeable.
- Both are verified manually against the real GitHub PR flow once the workflow and branch protection rule are in place — no separate automated test of the pipeline itself is warranted at this scope.

## Error handling

None beyond what `mvn verify` and GitHub Actions already provide — a failing test fails the job, a failing job blocks the merge via the required status check. No custom retry/notification logic is in scope.
