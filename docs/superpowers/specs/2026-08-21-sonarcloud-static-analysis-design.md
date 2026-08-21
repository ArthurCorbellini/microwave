# SonarCloud Static Analysis — Design

**Date:** 2026-08-21
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 3.1 — Static analysis (SonarCloud)

## Purpose

Add SonarCloud static analysis (bugs, code smells, vulnerabilities, JaCoCo coverage) as a new GitHub Actions job running on every PR, for each of the 5 services, advisory-only — reports findings without blocking merges.

## Motivation

There is currently no static analysis integrated into the repo — no CI step, no Maven plugin config, no `sonar-project.properties`. Whatever findings show up today come from a local SonarQube for IDE (SonarLint) extension, analyzing open files with no project-level config, not reproducible via any command in the repo.

This was prompted by Phase 3's review: two deprecated-class findings (`Jackson2JsonMessageConverter`, `JsonDeserializer` → their Jackson 3 successors) slipped through 20+ task-level reviews and the final whole-branch review, because nobody happened to run with deprecation warnings enabled. This is exactly the class of thing a static analyzer catches automatically and consistently.

## Scope

- One new GitHub Actions job (`sonar`), matrixed over the 5 services, added to `.github/workflows/ci.yml` alongside the existing `test` and `docker-build` jobs.
- SonarCloud (SaaS, free tier for public repos) — not self-hosted SonarQube.
- JaCoCo coverage bundled in from the start (unit + Testcontainers integration tests), read by SonarCloud from `target/site/jacoco/jacoco.xml`.
- 5 separate SonarCloud projects (one per service), matching the repo's existing "5 independent Maven modules, no parent POM" reality and the matrix-job pattern already used by `test`/`docker-build`.
- Default "Sonar way" quality profile and default SonarCloud Quality Gate — no curation yet.
- **Advisory-only**: the `sonar` job is not added to `main`'s required status checks. Findings are visible on the SonarCloud dashboard and via SonarCloud's PR decoration comment, but never block a merge.

Out of scope (explicitly deferred, not part of this design):
- Curating a custom quality profile (revisit once real noise from "Sonar way" has been observed).
- Making `sonar` a required/blocking check (a future decision, once the profile is curated and false-positive rate is known).
- A single multi-module SonarCloud project aggregating all 5 services (would require simulating an aggregated build across independent poms — real friction with no current benefit).
- SonarQube self-hosted (rejected in favor of SonarCloud's free tier for public repos — no infra to maintain for a solo learning project).

## Design

### Prerequisites (manual, outside the repo)

Before the workflow can run, the author must, outside of any code change:
1. Create/log into a SonarCloud organization linked to the GitHub account.
2. Import the 5 service repositories as 5 SonarCloud projects (this assigns their project keys).
3. Generate a SonarCloud token and add it as the `SONAR_TOKEN` secret in the GitHub repository's Actions secrets.

This is a one-time manual setup step, documented in the implementation plan, not something automated by the workflow itself.

### CI workflow: new `sonar` job

A new job, separate from `test`, matrixed the same way:

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

Design decisions:

- **Separate job, not a step inside `test`.** `test` is a required status check today. A step-level failure in the Sonar analysis (network blip, token issue, SonarCloud outage) would otherwise be able to fail `test` and block merges for reasons unrelated to test correctness — defeating the point of "advisory-only." Isolating it in its own job means a `sonar` failure can never affect `test`'s pass/fail.
- **`sonar-maven-plugin` invoked via fully-qualified coordinate on the command line (`org.sonarsource.scanner.maven:sonar-maven-plugin:5.1.0.4751:sonar`), not declared in any `pom.xml`.** With 5 independent poms and no parent, declaring the plugin means repeating (and later re-editing, on every version bump) the same block 5 times. Invoking it by full coordinate from the workflow keeps the plugin version pinned in exactly one place. The `-Dsonar.*` properties (organization, per-service projectKey, host URL) are passed the same way either way, since `projectKey` varies per service and doesn't belong hardcoded in a pom that's otherwise generic across services.
- `mvn verify` re-runs inside this job (rebuilding and re-testing) because it's the same command that produces the JaCoCo report the Sonar step needs to read — a duplicated build cost accepted in exchange for job isolation.
- `fetch-depth: 0` on checkout, since SonarCloud's analysis (new-code detection, blame) needs full git history, unlike `test`'s shallow default.
- Not added to `main`'s branch protection required checks — this is what makes the gate advisory rather than blocking.

### `pom.xml` changes: JaCoCo in all 5 services

Each of the 5 service `pom.xml` files gets a `jacoco-maven-plugin` block added to `<build><plugins>`:

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

- `prepare-agent` instruments code for coverage before tests run (default `initialize` phase binding).
- `report`, bound to the `verify` phase, runs after `maven-failsafe-plugin`'s integration tests, so the generated `target/site/jacoco/jacoco.xml` covers both unit tests (`test` phase) and Testcontainers integration tests (`verify` phase) — not just one.
- `sonar.coverage.jacoco.xmlReportPaths` is left unset since it already defaults to this report's location; no extra Sonar property needed for coverage to be picked up.

This is a real, repeated 5x edit — there's no parent POM to centralize it in, and creating one is out of scope here.

### SonarCloud project configuration

Each of the 5 SonarCloud projects keeps the default "Sonar way" quality profile and the default SonarCloud Quality Gate. The Quality Gate's pass/fail state is visible on the SonarCloud dashboard and in the PR decoration comment, but is never surfaced to GitHub as a required status check — that's what keeps this advisory-only, not the profile or gate configuration itself.

### Roadmap update

Add a new entry to `docs/roadmap.md`, positioned between Phase 3 and Phase 4:

> ### Phase 3.1 — Static analysis (SonarCloud)
>
> GitHub Actions job running SonarCloud analysis (bugs, code smells, vulnerabilities, JaCoCo coverage) for each of the 5 services on every PR, advisory-only — reports findings via SonarCloud's PR decoration, but is not a required status check yet.
>
> Focus: closing the gap where static-analysis findings only surfaced ad hoc via local SonarLint, not reproducibly in CI (prompted by deprecated Jackson API usage slipping through Phase 3's review).
>
> **Done when:** a PR triggers a `sonar` check for each of the 5 services, results (including coverage) are visible on SonarCloud's dashboard and as a PR comment, and none of it blocks merging.

## Testing

Like Phase 1.1's CI pipeline, this feature's own "test" is behavioral, not a Maven test suite:
- A test PR shows 5 `sonar (*)` checks running (alongside the existing `test (*)` and `docker-build (*)` checks).
- SonarCloud's PR decoration comment appears with per-service findings and coverage numbers.
- The PR remains mergeable regardless of Sonar findings or Quality Gate status — proving the gate is advisory, not blocking.
- Verified manually against the real GitHub PR flow once the workflow, `pom.xml` changes, and SonarCloud projects are in place — no separate automated test of the pipeline itself is warranted at this scope.

## Error handling

None beyond what `mvn verify`, the Sonar scanner, and GitHub Actions already provide. A failing `sonar` job step (bad token, SonarCloud outage, analysis error) fails only that job — isolated from `test` by design — and blocks nothing, consistent with advisory-only scope. No custom retry/notification logic is in scope.
