# Microwave

Learning project: an e-commerce system built incrementally to practice microservices in Java, messaging (RabbitMQ/Kafka), containerization (Docker), and orchestration (Kubernetes), with an optional later phase on provisioning real cloud infrastructure via Terraform.

## Where to look before doing anything

- **`docs/roadmap.md`** — the living roadmap: all phases, what each is for, "done when" criteria, and deferred decisions (e.g. API Gateway, cloud provider choice). Start here to understand what phase the project is in and why.
- **`docs/tech-debt.md`** — living list of known, *deliberate* limitations (e.g. TD-1: orders can get stuck in `CREATED` if `payments` is unreachable). Check this before "fixing" something that looks like a bug — it may be intentional and already tracked.
- **`docs/development-setup.md`** — how to get a working dev environment (Dev Container or local install).
- **`docs/superpowers/specs/`** and **`docs/superpowers/plans/`** — dated, point-in-time design specs and implementation plans (one per phase). These are snapshots of a decision made on a given date — don't edit them to reflect new decisions; the living docs above (`roadmap.md`, `tech-debt.md`) are where ongoing state lives instead.
- **`scripts/`** — `setup-devcontainer.sh` detects Docker or Podman and generates `.devcontainer/devcontainer.json` from the matching template. Must be run manually once before opening the project in a Dev Container (it generates the very file the Dev Containers extension needs, so it can't be a `postCreateCommand`/`initializeCommand` inside that file — chicken-and-egg).

## Conventions

- All code, comments, and documentation (including specs/plans) are in English.
- New phases go through the full cycle: brainstorm → spec in `docs/superpowers/specs/` → plan in `docs/superpowers/plans/` → implementation. Don't skip straight to code for anything beyond a trivial fix.
