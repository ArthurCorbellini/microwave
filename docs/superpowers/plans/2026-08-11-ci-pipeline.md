# CI Pipeline (test gate) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a GitHub Actions workflow that runs each service's test suite (unit + Testcontainers integration tests) on every PR against `main`, and a branch protection rule that blocks merging when any service's checks fail.

**Architecture:** A single GitHub Actions workflow (`.github/workflows/ci.yml`) using a matrix strategy over the three services (`catalog`, `orders`, `payments`), each running `mvn -B verify` in its own directory in parallel, with `fail-fast: false`. A branch protection rule on `main`, configured via `gh api`, requires all three resulting checks (`test (catalog)`, `test (orders)`, `test (payments)`) to pass before a PR can merge.

**Tech Stack:** GitHub Actions, `actions/checkout@v4`, `actions/setup-java@v4` (Temurin 25), Maven 3.9.16, `gh` CLI.

**Design source:** `docs/superpowers/specs/2026-08-11-ci-pipeline-design.md`

## Global Constraints

- Java 25 (Temurin distribution) — matches `mise.toml`'s `temurin-25.0.4+7.0.LTS` (CI floats to the latest `25` patch via `setup-java`, not pinned to the exact patch — acceptable per the approved design, no separate task needed to reconcile this).
- Must run `mvn -B verify`, not `mvn test` — `maven-failsafe-plugin` binds `*IT.java` Testcontainers tests to the `integration-test`/`verify` goals.
- All work happens on branch `phase1.1-ci-pipeline` (already exists, currently has the spec + roadmap commit `9606647`), PR opened against `main`.
- No Docker image build/push, no linting, no path-based filtering — explicitly out of scope per the design.
- Branch protection: `strict: false` (a PR branch doesn't need to be rebased/up-to-date with `main` first — not part of what was asked, keep minimal), `enforce_admins: false` (repo owner can still bypass in an emergency — flagged here so it's easy to correct if a stricter default is wanted).

---

### Task 1: Local sanity check — confirm all 3 services currently pass `mvn verify`

**Files:** none created/modified — verification only.

**Interfaces:**
- Consumes: nothing.
- Produces: confidence that a red check in later tasks means the CI setup is wrong, not that a pre-existing bug in the codebase is being blamed on it.

- [ ] **Step 1: Run `mvn verify` in `catalog`**

Run: `cd services/catalog && mvn -B verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 2: Run `mvn verify` in `orders`**

Run: `cd services/orders && mvn -B verify`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run `mvn verify` in `payments`**

Run: `cd services/payments && mvn -B verify`
Expected: `BUILD SUCCESS`.

If any of the three fail, stop — fixing that failure is outside this plan's scope; report it before continuing.

---

### Task 2: Create the GitHub Actions workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: a workflow file GitHub will pick up as soon as it's pushed (Task 3 verifies this).

- [ ] **Step 1: Create the workflow file**

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

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add GitHub Actions workflow running each service's test suite"
```

---

### Task 3: Push, open the PR, verify the workflow runs and passes

**Files:** none — this task pushes and observes, doesn't edit files.

**Interfaces:**
- Consumes: `.github/workflows/ci.yml` from Task 2, spec+roadmap commit already on the branch.
- Produces: an open PR with 3 green checks, consumed by Task 4 (branch protection) and Task 5 (failure verification).

- [ ] **Step 1: Push the branch**

Run: `git push -u origin phase1.1-ci-pipeline`

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title "Phase 1.1: CI pipeline (test gate)" --body "$(cat <<'EOF'
## Summary
- Add .github/workflows/ci.yml: runs mvn verify for catalog/orders/payments in parallel on every PR and push to main.
- See docs/superpowers/specs/2026-08-11-ci-pipeline-design.md for the design this implements.

## Test plan
- [ ] All 3 checks (test (catalog), test (orders), test (payments)) pass on this PR
- [ ] Branch protection on main configured to require all 3 (done in a follow-up commit on this same PR)
EOF
)" --base main --head phase1.1-ci-pipeline
```

- [ ] **Step 3: Watch the checks run**

Run: `gh pr checks --watch`
Expected: all three checks (`test (catalog)`, `test (orders)`, `test (payments)`) report `pass`.

---

### Task 4: Configure branch protection on `main`

**Files:** none — this is a repository setting, not a versioned file.

**Interfaces:**
- Consumes: the three check names (`test (catalog)`, `test (orders)`, `test (payments)`) produced by Task 2's job names.
- Produces: a protection rule consumed by Task 5's verification.

- [ ] **Step 1: Apply the protection rule**

```bash
gh api \
  --method PUT \
  repos/{owner}/{repo}/branches/main/protection \
  --input - <<'EOF'
{
  "required_status_checks": {
    "strict": false,
    "checks": [
      {"context": "test (catalog)"},
      {"context": "test (orders)"},
      {"context": "test (payments)"}
    ]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null
}
EOF
```

- [ ] **Step 2: Verify the rule was saved correctly**

Run: `gh api repos/{owner}/{repo}/branches/main/protection --jq '.required_status_checks.checks'`
Expected: a JSON array containing the three contexts (`test (catalog)`, `test (orders)`, `test (payments)`).

---

### Task 5: Verify the gate actually blocks a failing PR

**Files:**
- Create (temporary, deleted in Step 5): `services/orders/src/test/java/com/microwave/orders/order/CiGateFailureCheckTest.java`

**Interfaces:**
- Consumes: the open PR from Task 3, the protection rule from Task 4.
- Produces: confirmation that the spec's "Done when" criterion holds, before the PR is left ready for real review/merge.

- [ ] **Step 1: Add a deliberately failing test**

```java
package com.microwave.orders.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

class CiGateFailureCheckTest {

  @Test
  void alwaysFails() {
    fail("intentional failure to verify the CI gate blocks a red PR");
  }
}
```

- [ ] **Step 2: Commit and push**

```bash
git add services/orders/src/test/java/com/microwave/orders/order/CiGateFailureCheckTest.java
git commit -m "test: intentional failing test to verify CI gate (temporary)"
git push
```

- [ ] **Step 3: Confirm the check fails and the PR is blocked**

Run: `gh pr checks --watch`
Expected: `test (orders)` reports `fail`; `test (catalog)` and `test (payments)` still report `pass` (proves `fail-fast: false` and per-service isolation).

Run: `gh pr view --json mergeable,mergeStateStatus -q '.'`
Expected: `mergeStateStatus` is `BLOCKED` (not `CLEAN`) — the required-checks rule from Task 4 is preventing merge.

- [ ] **Step 4: Revert the intentional failure**

```bash
git rm services/orders/src/test/java/com/microwave/orders/order/CiGateFailureCheckTest.java
git commit -m "test: revert intentional CI gate failure check"
git push
```

- [ ] **Step 5: Confirm the PR is green and mergeable again**

Run: `gh pr checks --watch`
Expected: all three checks report `pass`.

Run: `gh pr view --json mergeable,mergeStateStatus -q '.'`
Expected: `mergeStateStatus` is `CLEAN`.

---

## Done

At this point: `.github/workflows/ci.yml` exists and runs on every PR/push to `main`; branch protection on `main` requires all 3 checks; both directions (red blocks, green allows) have been verified against the real GitHub PR flow — matching the spec's "Done when" criterion exactly. The PR is left open, green, and ready for the user's own review before merging (not auto-merged, consistent with how the last PR was handled).
