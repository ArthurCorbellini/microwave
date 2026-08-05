# Development Setup

Two ways to get a working environment for this project: **Dev Container** (recommended) or **local install**. Both end up in the same place — Java 25, Maven, and a container engine reachable by Testcontainers — they just differ in where Java/Maven actually run.

## Option A — Dev Container (recommended)

Everything (Java 25, Maven, the Java/Spring VS Code extensions, the Claude Code extension) runs inside a container, defined in `.devcontainer/`. Nothing gets installed on your machine directly, and the environment is identical every time, on any machine.

**Requirements:** VS Code with the [Dev Containers extension](https://marketplace.visualstudio.com/items?itemName=ms-vscode-remote.remote-containers), and a working Docker or Podman.

> **Reminder if you're on Podman:** VS Code needs to know to call `podman-remote` (or `podman`) instead of `docker`. This can't live in this repo (it's a VS Code setting scoped to "all profiles", not per-project), so add it to your own `settings.json` once:
> ```json
> { "dev.containers.dockerPath": "podman-remote" }
> ```
>
> **Also on Podman:** the first "Reopen in Container" for a new checkout can fail with a `TypeError` from the extension (a known race condition on its side, not this repo's config). Just try "Reopen in Container" again — it's reliable after that.

**Steps:**

1. Run the setup script once — it detects Docker or Podman and generates `.devcontainer/devcontainer.json`:
   ```bash
   ./scripts/setup-devcontainer.sh
   ```
2. Open the project folder in VS Code.
3. Command Palette (Ctrl+Shift+P) → **Dev Containers: Reopen in Container**.
4. Wait for the build (first time only — pulls the base image and installs Maven). VS Code reconnects itself inside the container automatically.

That's it — `mvn`, `java`, and the Java/Spring extensions are all available inside the integrated terminal, and Testcontainers can reach the container engine without any extra setup.

Re-run `./scripts/setup-devcontainer.sh` any time you switch machines or engines — it regenerates `.devcontainer/devcontainer.json` (which is gitignored, since it's a generated file) from the matching template in `.devcontainer/devcontainer-docker.json` / `devcontainer-podman.json`.

## Option B — Local install (no containers for the app itself)

Everything runs directly on your machine. More initial setup, but no dependency on the Dev Containers extension or on the exact templates in this repo.

**Steps:**

1. Install Java 25 and Maven, however you prefer (system package manager, manual install, or a version manager like [SDKMAN](https://sdkman.io) if you want them isolated to your user instead of system-wide).
2. Install Docker or Podman, and make sure it's running — Testcontainers needs it for the integration tests regardless of which option you pick here. If using Podman rootless, also disable Ryuk (see the note in `.devcontainer/devcontainer-podman.json` for why) by exporting:
   ```bash
   export TESTCONTAINERS_RYUK_DISABLED=true
   ```
3. Install the Java/Spring extensions in your own editor — for VS Code: [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) and [Spring Boot Extension Pack](https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack).
4. Run the services directly, e.g.:
   ```bash
   mvn -f services/catalog/pom.xml test
   ```

## Why Dev Container is the default recommendation

- **Reproducible** — the exact same Java/Maven versions, every machine, no "works on my machine" drift.
- **Nothing touches your host** — no global Java/Maven install to clean up later.
- **Less to get wrong** — Option B has more manual steps (SDKMAN, Ryuk env var, editor extensions) that are easy to skip or get out of sync.

The trade-off is a bit more upfront setup (the container build) and a dependency on the Dev Containers extension itself. If you'd rather not use it, Option B is fully supported.
