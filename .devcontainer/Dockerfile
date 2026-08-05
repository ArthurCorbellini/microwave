FROM mcr.microsoft.com/devcontainers/java:25-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends maven curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# docker CLI only (no daemon) — talks to whatever socket DOCKER_HOST points at
# (set in devcontainer.json), which is either the real Docker socket or
# Podman's Docker-API-compatible one, both bind-mounted from the host.
# Installed from a plain Dockerfile RUN instead of the "docker-outside-of-docker"
# devcontainer Feature: Features rely on Buildx "additional build contexts",
# which podman-remote can't transfer to a remote daemon — the exact bug fixed
# earlier when Maven was moved off the Java feature. See docs/development-setup.md.
RUN curl -fsSL https://download.docker.com/linux/static/stable/x86_64/docker-29.7.1.tgz \
    | tar -xz -C /usr/local/bin --strip-components=1 docker/docker
