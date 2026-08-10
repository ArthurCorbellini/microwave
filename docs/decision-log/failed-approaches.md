# Failed Approaches

Living log of approaches that were tried in the code and failed, so future sessions (human or Claude) don't re-propose and re-debug the same dead end. Each entry is a small postmortem: what was tried, why it looked reasonable, why it actually failed (with evidence, not just a guess), and what replaced it.

This is different from the decision log's other categories:

- `docs/decision-log/tech-debts.md` tracks limitations *in the current, accepted design* — something that exists and works, just imperfectly. `docs/decision-log/failed-approaches.md` tracks things that are **not** part of the current design at all.
- `docs/decision-log/rejected-approaches.md` covers the same "not part of the design" territory, but reached by reasoning alone, without writing code. `docs/decision-log/failed-approaches.md`'s entries were actually implemented and then reverted, with concrete evidence of why they failed.

## Entry template

New entries should follow this structure:

```markdown
### FA-N — <short title>

**Tried in:** <phase/context>

<1-2 sentences: what was actually attempted>

**Why it looked reasonable:** <the argument that made this worth trying>

**Why it failed:** <concrete, verifiable evidence — error message, reproduction rate, upstream issue link>

**What replaced it:** <the actual current solution>

**Lesson:** <the generalizable takeaway, if there is one beyond "this specific tool has this specific bug">

**Reconsider if:** <the external condition that would justify retrying this>
```

## Entries

### FA-1 — VS Code Dev Container for the dev environment

**Tried in:** Phase 1 (dev environment setup, before any application code)

A full containerized dev environment: Java 25 + Maven baked into a Docker/Podman image via `.devcontainer/`, the whole VS Code editor running inside it via "Reopen in Container".

**Why it looked reasonable:** reproducible across machines, nothing installed on the host, a clean "clone the repo and it just works" story — appealing for a portfolio project.

**Why it failed:** hit a real, unresolved race condition in the VS Code Dev Containers extension itself when paired with Podman — `TypeError: Cannot read properties of null (reading 'status')`, thrown right after the container starts. Confirmed via the extension's own open-source code that this is *not* a Docker/Podman event-format mismatch (that path is handled correctly, checking `status`/`Status`/`Action` explicitly for exactly this reason). The real cause is elsewhere in the extension's internals — not something reachable via this repo's config. Measured empirically at roughly 75% failure rate on first container creation across repeated trials; clearing all image/container cache did not fix it.

**What replaced it:** [`mise`](https://mise.jdx.dev) for pinning Java/Maven versions (`mise.toml` / `mise.local.toml`), with Testcontainers reaching Docker or Podman directly via `DOCKER_HOST` — no containerized dev environment at all. See `docs/development-setup.md`.

**Lesson:** the underlying problem — "get the right JDK version, reproducibly" — didn't need a full containerized dev environment in the first place. That's normally a version manager's job (SDKMAN, `mise`, `asdf`). Full Dev Containers earn their cost when the *toolchain itself* is complex (multiple languages, hard-to-replicate system dependencies) — a single JDK + Maven never met that bar here.

**Reconsider if:** the VS Code Dev Containers extension fixes this race condition upstream, or a future phase's toolchain grows complex enough (multiple languages, hard-to-replicate system dependencies) to justify a containerized dev environment on its own merits, independent of this bug.

### FA-2 — `container-engine` wrapper script for `dev.containers.dockerPath`

**Tried in:** Phase 1, as part of FA-1's Dev Container setup

A committed wrapper script (`.vscode/bin/container-engine`) that `dev.containers.dockerPath` pointed to, detecting Docker vs. Podman at invocation time and `exec`-ing into whichever was found — so the VS Code setting itself could be one stable, committed value instead of something each developer sets personally.

**Why it looked reasonable:** validated directly via the `devcontainers` CLI multiple times, including full container startup — the mechanism genuinely worked when invoked manually.

**Why it failed:** didn't work reliably from the real VS Code UI ("Reopen in Container"), despite working via direct CLI invocation. The extension appears to need `dev.containers.dockerPath` set directly in personal/global `settings.json` to function correctly — a workspace-committed path to an executable wasn't equivalent in practice, for reasons not fully diagnosed.

**What replaced it:** moot once FA-1 was abandoned — no wrapper script is needed since there's no Dev Container setup left to configure.

**Lesson:** `dev.containers.dockerPath` has to be a personal/global setting, not a workspace-committed value — the extension doesn't honor a committed path reliably from the real "Reopen in Container" UI, even though the exact same path works fine via direct CLI invocation.

**Reconsider if:** Dev Containers are reintroduced (see FA-1's `Reconsider if`) — at that point, set `dev.containers.dockerPath` as a personal setting from the start rather than trying to commit it to the workspace again.

### FA-3 — Dev Container "Features" with `podman-remote`

**Tried in:** Phase 1, as part of FA-1's Dev Container setup (first for the Java/Maven feature, later again for `docker-outside-of-docker`)

Using the standard Dev Container Features mechanism (`ghcr.io/devcontainers/features/*`) to install tooling into the container image, instead of writing Dockerfile `RUN` steps by hand.

**Why it looked reasonable:** Features are the idiomatic, documented way to add tools to a dev container — less to get wrong than hand-rolled Dockerfile steps.

**Why it failed:** Features rely on Docker Buildx's "additional build contexts" mechanism to inject their content into the build. `podman-remote` (a *remote* client — the build happens on a different daemon than the one issuing the command) can't transfer that build context to the remote side. Fails with `invalid additional build context format`, confirmed against known upstream Podman/buildah issues describing the same limitation.

**What replaced it:** plain Dockerfile `RUN` steps (`apt-get install maven`; downloading the static `docker` CLI binary) — same end result, no Features mechanism involved.

**Lesson:** Dev Container Features and `podman-remote` are incompatible, full stop, independent of anything else in this project. If containers come up again for something else (Phase 2's docker-compose, Phase 4's Kubernetes tooling), don't reach for Features if Podman-remote is in the picture.

**Reconsider if:** this specific limitation is fixed upstream (Podman/Buildah's handling of Buildx additional build contexts under a remote client), or the project switches from `podman-remote` to Docker or local Podman as its container engine.
