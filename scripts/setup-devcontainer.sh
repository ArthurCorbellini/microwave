#!/usr/bin/env bash
# Detects which container engine is available (Docker or Podman) and
# generates .devcontainer/devcontainer.json from the matching template.
# Run this once before opening the project in VS Code, and again any time
# you switch machines/engines.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEVCONTAINER_DIR="$SCRIPT_DIR/../.devcontainer"

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    echo "Docker detected — using devcontainer-docker.json"
    cp "$DEVCONTAINER_DIR/devcontainer-docker.json" "$DEVCONTAINER_DIR/devcontainer.json"
elif command -v podman-remote >/dev/null 2>&1 && podman-remote info >/dev/null 2>&1; then
    echo "Podman (remote) detected — using devcontainer-podman.json"
    cp "$DEVCONTAINER_DIR/devcontainer-podman.json" "$DEVCONTAINER_DIR/devcontainer.json"
elif command -v podman >/dev/null 2>&1 && podman info >/dev/null 2>&1; then
    echo "Podman detected — using devcontainer-podman.json"
    cp "$DEVCONTAINER_DIR/devcontainer-podman.json" "$DEVCONTAINER_DIR/devcontainer.json"
else
    echo "Error: no working Docker or Podman found." >&2
    echo "Install one of them, or generate .devcontainer/devcontainer.json manually" >&2
    echo "from one of the templates in .devcontainer/." >&2
    exit 1
fi

echo "Wrote $DEVCONTAINER_DIR/devcontainer.json"
