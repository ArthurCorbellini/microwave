# Development Setup

Java 25 and Maven, pinned per-project via [mise](https://mise.jdx.dev), plus a container engine (Docker or Podman) reachable by Testcontainers. No containerized dev environment — the application code runs natively; only its test/runtime *dependencies* (Postgres, etc.) run in containers, via Testcontainers.

## Setup

1. Install [mise](https://mise.jdx.dev) (isolated to your user, no root/sudo):
   ```bash
   curl https://mise.run | sh
   ```
   Then add it to your shell (one-time):
   ```bash
   echo 'eval "$(~/.local/bin/mise activate bash)"' >> ~/.bashrc
   ```
   (use the equivalent for your shell if not bash — see mise's docs)

2. From the project root, trust and let mise install the pinned versions:
   ```bash
   mise trust
   mise install
   ```
   This reads `mise.toml` and installs exactly Java 25 (Temurin) and Maven 3.9.16 into `~/.local/share/mise`, isolated from any other project or system install. `java`/`mvn` now resolve to these versions automatically whenever your shell is in this directory (and fall back to your system defaults everywhere else) — no manual activation step needed.

3. Make sure Docker or Podman is running — Testcontainers needs it for the integration tests. If Docker's default socket works for you, you're done: skip to step 4.

   **If you're on Podman specifically**, Testcontainers needs to be told where the socket is, and Podman rootless doesn't reliably support Ryuk (Testcontainers' cleanup sidecar). Create `mise.local.toml` in the project root (gitignored — this is personal, not shared) with:
   ```toml
   [env]
   DOCKER_HOST = "unix://{{env.XDG_RUNTIME_DIR}}/podman/podman.sock"
   TESTCONTAINERS_RYUK_DISABLED = "true"
   ```
   Ryuk being disabled means test containers aren't auto-cleaned if a run crashes; run `podman container prune` (or `podman-remote container prune`, if that's how you reach Podman) occasionally if they pile up.

4. Install the Java/Spring extensions in your editor — for VS Code: [Extension Pack for Java](https://marketplace.visualstudio.com/items?itemName=vscjava.vscode-java-pack) and [Spring Boot Extension Pack](https://marketplace.visualstudio.com/items?itemName=vmware.vscode-boot-dev-pack).

5. Run the services directly, e.g.:
   ```bash
   mvn -f services/catalog/pom.xml test
   ```
