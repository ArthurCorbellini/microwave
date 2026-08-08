# Microwave

Learning project: an e-commerce system built incrementally to practice microservices in Java, messaging (RabbitMQ/Kafka), containerization (Docker), and orchestration (Kubernetes), with an optional later phase on provisioning real cloud infrastructure via Terraform.

## Where to look before doing anything

- **`docs/roadmap.md`** — the living roadmap: all phases, what each is for, "done when" criteria, and deferred decisions tied to *when* something joins the roadmap (e.g. API Gateway, cloud provider choice). Start here to understand what phase the project is in and why.
- **`docs/tech-debt.md`** — living list of known limitations: shortcuts whose cost of fixing grows the longer they're left unaddressed (e.g. TD-1: orders can get stuck in `CREATED` if `payments` is unreachable). Check this before "fixing" something that looks like a bug — it may be intentional and already tracked.
- **`docs/scope-decisions.md`** — living list of things deliberately *not* built or adopted, with no compounding cost either way (e.g. SD-1: no shopping cart; SD-2: MapStruct not adopted for entity↔DTO mapping). Check this before re-proposing a feature or dependency — it may have already been evaluated and declined.
- **`docs/rejected-approaches.md`** — living list of things tried *in the code* and abandoned, with why (e.g. RA-1: VS Code Dev Container for the dev environment). Check this before re-proposing something — it may already be a known dead end.
- **`docs/development-setup.md`** — how to get a working dev environment: Java/Maven via `mise` (see `mise.toml`), plus Testcontainers reaching Docker or Podman.
- **`docs/superpowers/specs/`** and **`docs/superpowers/plans/`** — dated, point-in-time design specs and implementation plans (one per phase). These are snapshots of a decision made on a given date — don't edit them to reflect new decisions; the living docs above (`roadmap.md`, `tech-debt.md`) are where ongoing state lives instead.
- **`mise.toml`** (committed) / **`mise.local.toml`** (gitignored, personal — e.g. Podman's `DOCKER_HOST`) — see `docs/development-setup.md`.

## Conventions

- All code, comments, and documentation (including specs/plans) are in English.
- New phases go through the full cycle: brainstorm → spec in `docs/superpowers/specs/` → plan in `docs/superpowers/plans/` → implementation. Don't skip straight to code for anything beyond a trivial fix.
- **Keeping the living docs (`roadmap.md`, `tech-debt.md`, `scope-decisions.md`, `rejected-approaches.md`) updated is part of the work, not an afterthought.** When you accept a limitation whose cost would grow over time, add it to `tech-debt.md`. When you decide not to build or adopt something (no compounding cost), add it to `scope-decisions.md`. When you try something in code and abandon it, add it to `rejected-approaches.md`. When a phase's status changes, update `roadmap.md`. Do this as you go, in the same turn as the decision — not "later" and not only in conversation history, which the next session won't have. The decision itself should already have been discussed with the user before this point — don't ask again just to log it; write the entry and say you did, so it's easy to correct if it shouldn't be there.
