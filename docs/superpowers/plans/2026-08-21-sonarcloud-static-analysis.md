# SonarCloud Static Analysis (Phase 3.1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add SonarCloud static analysis (bugs, code smells, vulnerabilities, JaCoCo coverage) as a new, advisory-only GitHub Actions job running on every PR, for each of the 5 services.

**Architecture:** A new `sonar` job in `.github/workflows/ci.yml`, matrixed over the 5 services, separate from the existing `test` job so a Sonar-side failure can never affect the required `test` check. Each matrix leg runs `mvn -B verify org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar` in its service directory, reading `SONAR_TOKEN` from a GitHub Actions secret. The `sonar-maven-plugin` is invoked by full coordinate on the command line, not declared in any `pom.xml`. Coverage comes from `jacoco-maven-plugin`, added to each of the 5 services' `pom.xml`, bound to `prepare-agent` (before tests) and `report` (at the `verify` phase, after both unit and Testcontainers integration tests).

**Tech Stack:** GitHub Actions, `actions/checkout@v5`, `actions/setup-java@v5` (Temurin 25), Maven 3.9.16, `org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751`, `org.jacoco:jacoco-maven-plugin:0.8.13`, SonarCloud, `gh` CLI.

**Spec:** `docs/superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md`

## Global Constraints

- SonarCloud (SaaS), not self-hosted SonarQube.
- **Advisory-only**: the `sonar` job is never added to `main`'s required status checks — a red `sonar` check must never block a PR from merging.
- `sonar-maven-plugin` version `5.1.0.4751` is invoked via full coordinate (`org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar`) on the command line in the workflow — it is never declared as a `<plugin>` in any `pom.xml`.
- `jacoco-maven-plugin` version `0.8.13` is added to all 5 service `pom.xml` files (`services/{catalog,orders,payments,inventory,notifications}/pom.xml`).
- Default "Sonar way" quality profile and default SonarCloud Quality Gate — no custom profile curation in this plan.
- 5 separate SonarCloud projects (one per service), not one aggregated multi-module project.
- Assumed SonarCloud org key: `arthurcorbellini`. Assumed project keys: `ArthurCorbellini_microwave-<service>` (e.g. `ArthurCorbellini_microwave-catalog`). Task 1 confirms these against what SonarCloud actually assigns — if either differs, Task 3's workflow YAML must use the real values instead.
- All work happens on branch `phase3.1-sonarcloud-static-analysis` (already exists, currently holds only the spec commit `b701458`), PR opened against `main`.
- No branch protection changes, no custom quality profile, no single multi-module Sonar project, no self-hosted SonarQube — explicitly out of scope per the design.

---

### Task 1: Manual prerequisite — SonarCloud org, 5 projects, and `SONAR_TOKEN` secret

**🧑 Human action required.** This task cannot be performed by an agent — it requires interactive login and clicking through the SonarCloud web UI. If you are an agent executing this plan, stop here, ask the human operator to complete the steps below, and only proceed to Task 2 once they confirm it's done (Step 5 gives you a command to verify without needing them to paste secrets).

**Files:** none — this is external SaaS configuration, not a repo change.

**Interfaces:**
- Consumes: nothing.
- Produces: a SonarCloud org key, 5 project keys, and a `SONAR_TOKEN` GitHub Actions secret, consumed by Task 3 (workflow) and Task 5 (verification).

- [ ] **Step 1: Create/confirm the SonarCloud organization**

Log into [sonarcloud.io](https://sonarcloud.io) with the GitHub account that owns `ArthurCorbellini/microwave`, and create an organization (or use the one SonarCloud auto-creates from the GitHub account). Note the org key shown in the URL (e.g. `sonarcloud.io/organizations/<org-key>`).

If it is **not** `arthurcorbellini`, write down the real value — it replaces every `-Dsonar.organization=arthurcorbellini` in Task 3.

- [ ] **Step 2: Create the 5 projects manually (not via GitHub auto-import)**

The repo `ArthurCorbellini/microwave` is a single GitHub repository containing 5 independent Maven modules — SonarCloud's "import from GitHub" flow assumes one project per repo, which doesn't fit. Instead, for each of `catalog`, `orders`, `payments`, `inventory`, `notifications`:

1. In the SonarCloud org, choose "Create Project" → "Manually" (not the GitHub-linked automatic import).
2. Project key: `ArthurCorbellini_microwave-<service>` (e.g. `ArthurCorbellini_microwave-catalog`) — must match exactly, since Task 3's workflow hardcodes this pattern.
3. Display name: `microwave-<service>`.
4. When asked how analysis will run, choose **"Other CI"** / **"With GitHub Actions"** manual setup (skip any auto-generated workflow — Task 3 already covers this) so SonarCloud doesn't try to create its own workflow file.

If any project key SonarCloud actually assigns differs from `ArthurCorbellini_microwave-<service>`, write down the real values — they replace the corresponding `-Dsonar.projectKey=...` in Task 3.

- [ ] **Step 3: Generate a token**

In SonarCloud, go to **My Account → Security → Generate Tokens**. A single token scoped to the organization (not tied to one project) is enough to cover all 5 projects. Copy it — it's shown only once.

- [ ] **Step 4: Add it as a GitHub Actions secret**

```bash
gh secret set SONAR_TOKEN --repo ArthurCorbellini/microwave
```

Paste the token when prompted (or pipe it in: `echo "$TOKEN" | gh secret set SONAR_TOKEN --repo ArthurCorbellini/microwave`).

- [ ] **Step 5: Verify the secret exists (without exposing its value)**

Run: `gh secret list --repo ArthurCorbellini/microwave`
Expected: `SONAR_TOKEN` appears in the list.

---

### Task 2: Add JaCoCo coverage to all 5 service `pom.xml` files

**Files:**
- Modify: `services/catalog/pom.xml`
- Modify: `services/orders/pom.xml`
- Modify: `services/payments/pom.xml`
- Modify: `services/inventory/pom.xml`
- Modify: `services/notifications/pom.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: `target/site/jacoco/jacoco.xml` in each service after `mvn verify`, consumed by Task 3's `sonar:sonar` step (via the plugin's default `sonar.coverage.jacoco.xmlReportPaths`, no extra property needed).

- [ ] **Step 1: Add the plugin block to `services/catalog/pom.xml`**

Inside the existing `<build><plugins>` block (alongside `spring-boot-maven-plugin` and `maven-failsafe-plugin`), add:

```xml
<plugin>
    <groupId>org.jacoco</groupId>
    <artifactId>jacoco-maven-plugin</artifactId>
    <version>0.8.13</version>
    <executions>
        <execution>
            <goals>
                <goal>prepare-agent</goal>
            </goals>
        </execution>
        <execution>
            <id>report</id>
            <phase>verify</phase>
            <goals>
                <goal>report</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

- [ ] **Step 2: Run `mvn verify` in `catalog` and confirm the coverage report is produced**

Run: `cd services/catalog && mvn -B verify`
Expected: `BUILD SUCCESS`.

Run: `test -f services/catalog/target/site/jacoco/jacoco.xml && echo FOUND`
Expected: `FOUND`.

- [ ] **Step 3: Repeat Step 1 for `services/orders/pom.xml`**

Same `<plugin>` block, added to `orders`' existing `<build><plugins>`.

- [ ] **Step 4: Run `mvn verify` in `orders` and confirm the report**

Run: `cd services/orders && mvn -B verify`
Expected: `BUILD SUCCESS`.

Run: `test -f services/orders/target/site/jacoco/jacoco.xml && echo FOUND`
Expected: `FOUND`.

- [ ] **Step 5: Repeat Step 1 for `services/payments/pom.xml`**

Same `<plugin>` block, added to `payments`' existing `<build><plugins>`.

- [ ] **Step 6: Run `mvn verify` in `payments` and confirm the report**

Run: `cd services/payments && mvn -B verify`
Expected: `BUILD SUCCESS`.

Run: `test -f services/payments/target/site/jacoco/jacoco.xml && echo FOUND`
Expected: `FOUND`.

- [ ] **Step 7: Repeat Step 1 for `services/inventory/pom.xml`**

Same `<plugin>` block, added to `inventory`'s existing `<build><plugins>`.

- [ ] **Step 8: Run `mvn verify` in `inventory` and confirm the report**

Run: `cd services/inventory && mvn -B verify`
Expected: `BUILD SUCCESS`.

Run: `test -f services/inventory/target/site/jacoco/jacoco.xml && echo FOUND`
Expected: `FOUND`.

- [ ] **Step 9: Repeat Step 1 for `services/notifications/pom.xml`**

Same `<plugin>` block, added to `notifications`' existing `<build><plugins>`.

- [ ] **Step 10: Run `mvn verify` in `notifications` and confirm the report**

Run: `cd services/notifications && mvn -B verify`
Expected: `BUILD SUCCESS`.

Run: `test -f services/notifications/target/site/jacoco/jacoco.xml && echo FOUND`
Expected: `FOUND`.

- [ ] **Step 11: Commit**

```bash
git add services/catalog/pom.xml services/orders/pom.xml services/payments/pom.xml services/inventory/pom.xml services/notifications/pom.xml
git commit -m "build: add JaCoCo coverage reporting to all 5 services"
```

---

### Task 3: Add the `sonar` job to the CI workflow

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `SONAR_TOKEN` secret and the org/project keys from Task 1; `jacoco.xml` report path from Task 2 (implicitly, via the plugin's default).
- Produces: a `sonar (${{ matrix.service }})` check per service on every PR, consumed by Task 5's verification.

- [ ] **Step 1: Add the `sonar` job**

Add this job to `.github/workflows/ci.yml`, alongside `test` and `docker-build` (same file, top-level under `jobs:`):

```yaml
  sonar:
    name: sonar (${{ matrix.service }})
    runs-on: ubuntu-latest
    timeout-minutes: 20
    strategy:
      fail-fast: false
      matrix:
        service: [catalog, orders, payments, inventory, notifications]

    steps:
      - uses: actions/checkout@v5
        with:
          fetch-depth: 0

      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '25'
          cache: maven
          cache-dependency-path: services/${{ matrix.service }}/pom.xml

      - name: Analyze with SonarCloud
        working-directory: services/${{ matrix.service }}
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
        run: >
          mvn -B verify
          org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar
          -Dsonar.organization=arthurcorbellini
          -Dsonar.projectKey=ArthurCorbellini_microwave-${{ matrix.service }}
          -Dsonar.host.url=https://sonarcloud.io
```

If Task 1 recorded a different org key or project key pattern, substitute the real values in `-Dsonar.organization` and `-Dsonar.projectKey` before committing.

Do **not** add `sonar (*)` to any branch protection / required status checks configuration — leaving it out is what makes this advisory-only.

- [ ] **Step 2: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add advisory-only SonarCloud analysis job for all 5 services"
```

---

### Task 4: Update the roadmap

**Files:**
- Modify: `docs/roadmap.md`

**Interfaces:**
- Consumes: nothing.
- Produces: a "Phase 3.1" entry documenting this work, matching the project convention that living docs are updated in the same turn as the decision.

- [ ] **Step 1: Insert the new entry between the existing Phase 3 and Phase 4 sections**

In `docs/roadmap.md`, immediately after Phase 3's section (ending with its "Done when" line) and before the `### Phase 4 — Payments moves to asynchronous messaging` heading, insert:

```markdown
### Phase 3.1 — Static analysis (SonarCloud)

**Status:** Complete (2026-08-21). See [`docs/superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md`](superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md) and [`docs/superpowers/plans/2026-08-21-sonarcloud-static-analysis.md`](superpowers/plans/2026-08-21-sonarcloud-static-analysis.md) for the design and plan it was built from.

GitHub Actions job running SonarCloud analysis (bugs, code smells, vulnerabilities, JaCoCo coverage) for each of the 5 services on every PR, advisory-only — reports findings via SonarCloud's PR decoration, but is not a required status check yet.

Focus: closing the gap where static-analysis findings only surfaced ad hoc via local SonarLint, not reproducibly in CI (prompted by deprecated Jackson API usage slipping through Phase 3's review).

**Done when:** a PR triggers a `sonar` check for each of the 5 services, results (including coverage) are visible on SonarCloud's dashboard and as a PR comment, and none of it blocks merging.
```

- [ ] **Step 2: Update the "Next step" paragraph at the end of the file**

Add a sentence noting Phase 3.1 is complete, following the same pattern as the other completed phases already listed there (each links to its spec and plan).

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md
git commit -m "docs: mark Phase 3.1 (SonarCloud static analysis) in roadmap"
```

---

### Task 5: Push, open the PR, verify the checks run and stay advisory

**Files:** none — this task pushes and observes, doesn't edit files.

**Interfaces:**
- Consumes: all commits from Tasks 2-4, the SonarCloud setup from Task 1.
- Produces: an open PR with visible `sonar (*)` checks, proving both that analysis runs and that it doesn't block merging — the spec's "Done when" criterion.

- [ ] **Step 1: Push the branch**

Run: `git push -u origin phase3.1-sonarcloud-static-analysis`

- [ ] **Step 2: Open the PR**

```bash
gh pr create --title "Phase 3.1: SonarCloud static analysis" --body "$(cat <<'EOF'
## Summary
- Add jacoco-maven-plugin to all 5 services for coverage reporting.
- Add an advisory-only `sonar` job to .github/workflows/ci.yml, analyzing each service with SonarCloud.
- Update docs/roadmap.md with the Phase 3.1 entry.
- See docs/superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md for the design this implements.

## Test plan
- [ ] All 5 `sonar (*)` checks appear and complete on this PR
- [ ] SonarCloud PR decoration comment appears with per-service findings/coverage
- [ ] PR stays mergeable regardless of `sonar` check outcome (advisory-only, not a required check)
EOF
)" --base main --head phase3.1-sonarcloud-static-analysis
```

- [ ] **Step 3: Watch the checks run**

Run: `gh pr checks --watch`
Expected: `test (*)` and `docker-build (*)` checks pass as before; `sonar (*)` checks appear for all 5 services and complete (pass or fail — either is acceptable at this stage, since the gate is advisory).

- [ ] **Step 4: Confirm SonarCloud's PR decoration comment appears**

Run: `gh pr view --json comments -q '.comments[].body' | grep -i sonarcloud`
Expected: at least one comment body mentioning SonarCloud analysis results is present (SonarCloud posts this automatically once the GitHub App integration is linked to the org from Task 1; if nothing appears, check that the SonarCloud GitHub integration is installed on the `ArthurCorbellini/microwave` repo).

- [ ] **Step 5: Confirm the PR remains mergeable regardless of `sonar` outcome**

Run: `gh pr view --json mergeable,mergeStateStatus -q '.'`
Expected: `mergeStateStatus` is not `BLOCKED` because of the `sonar` checks — if `test` and `docker-build` are green, the PR should be `CLEAN` (or `UNSTABLE` at worst, if GitHub flags the non-required `sonar` failures without blocking) even if one or more `sonar (*)` checks are red. This is the direct proof that the gate is advisory, not blocking.

---

## Done

At this point: `.github/workflows/ci.yml` has an advisory-only `sonar` job running for all 5 services; all 5 `pom.xml` files produce JaCoCo coverage consumed by that job; `docs/roadmap.md` documents Phase 3.1; and the open PR demonstrates both that analysis runs and reports (dashboard + PR comment) and that it never blocks merging — matching the spec's "Done when" criterion exactly. The PR is left open, for the user's own review before merging (not auto-merged, consistent with how prior phase PRs were handled).
