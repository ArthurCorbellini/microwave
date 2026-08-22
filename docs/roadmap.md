# Roadmap — Microwave (Learning E-commerce)

**Date:** 2026-07-31
**Status:** roadmap approved; each phase gets its own detailed spec before implementation.

## Project goal

Learning project aimed at portfolio/technical interviews. Hands-on focus on:

- Microservices in Java (Spring Boot)
- Messaging with RabbitMQ and Kafka
- Containerization with Docker
- Orchestration with Kubernetes

Since this targets a portfolio, good practices, tests, and documentation matter from the start — this isn't just "make it work."

## Author context

Comfortable with Java/Spring. RabbitMQ, Kafka, Docker, and Kubernetes are new or lightly explored.

## Conventions

- **Language:** all project artifacts — code, comments, README, API docs, and these specs/plans — are written in English.
- Incremental approach: one layer of complexity at a time. Each phase below is an independent sub-project with its own cycle: brainstorm → approved spec → implementation plan → execution. Phase specs live in `docs/superpowers/specs/YYYY-MM-DD-<topic>-design.md`.

## Phases

### Phase 1 — Microservices foundation (synchronous REST)

**Status:** Complete (2026-08-06). All 3 services implemented, tested (unit + Testcontainers integration + WireMock Feign contract tests), and verified end-to-end locally.

Services: `catalog`, `orders`, `payments` (simulated). Spring Boot, one Postgres database per service, REST communication. No messaging, no containers yet — runs locally.

Focus: service boundaries, API contracts, "database per service," tests.

**Done when:** all 3 services run locally, each with its own database, and it's possible to create an order end-to-end (catalog → order → simulated payment) via REST calls, with automated tests covering the main flow.

### Phase 1.1 — Continuous Integration (test gate)

**Status:** Complete (2026-08-11). See [`docs/superpowers/specs/2026-08-11-ci-pipeline-design.md`](superpowers/specs/2026-08-11-ci-pipeline-design.md) and [`docs/superpowers/plans/2026-08-11-ci-pipeline.md`](superpowers/plans/2026-08-11-ci-pipeline.md) for the design and plan it was built from.

GitHub Actions workflow running each service's test suite (unit + Testcontainers integration tests) on every PR, gating merges to `main` via a required branch protection rule. Doesn't depend on Phase 2 — GitHub-hosted runners already have Docker available for Testcontainers.

Focus: closing the gap where PRs could merge without any automated verification (Phase 1's own PR merged with none).

**Done when:** a PR with a failing test in any of the 3 services cannot be merged into `main`, and a PR with all tests passing can.

### Phase 2 — Containerization

**Status:** Complete (2026-08-11). See [`docs/superpowers/specs/2026-08-11-phase2-containerization-design.md`](superpowers/specs/2026-08-11-phase2-containerization-design.md) and [`docs/superpowers/plans/2026-08-11-phase2-containerization.md`](superpowers/plans/2026-08-11-phase2-containerization.md) for the design and plan it was built from.

Dockerfile per service + docker-compose orchestrating all services and databases.

Focus: packaging, networking between containers, configuration via environment variables.

**Done when:** the full Phase 1 flow comes up and works with just `docker-compose up`, with nothing run manually from the IDE.

### Phase 3 — Asynchronous messaging (hybrid)

**Status:** Complete (2026-08-17). See [`docs/superpowers/specs/2026-08-14-phase3-messaging-design.md`](superpowers/specs/2026-08-14-phase3-messaging-design.md) and [`docs/superpowers/plans/2026-08-14-phase3-messaging.md`](superpowers/plans/2026-08-14-phase3-messaging.md) for the design and plan it was built from.

New services: `inventory` and `notifications`.

- **RabbitMQ** for point-to-point commands (e.g., Orders → Inventory "reserve item"). This is a task-queue/async-RPC pattern, not EDA — the publisher knows who should process it.
- **Kafka** for domain events (e.g., `OrderCreated`), consumed by Notifications and future services. This is EDA (event-driven): publisher and consumer are decoupled.

**Explicit decision:** keep the hybrid on purpose (not moving to pure EDA), to compare both messaging patterns hands-on. See brainstorming session discussion from 2026-07-31.

`orders` → `payments` stays synchronous REST in this phase, unchanged since Phase 1 — its migration to RabbitMQ is Phase 4's scope, not this one. `TD-1` (orders stuck in `CREATED` when `payments` is unreachable) therefore stays open until Phase 4.

Focus: distinguishing command queues from event streams, eventual consistency, idempotency.

**Done when:** creating an order triggers an inventory reservation via RabbitMQ and generates a notification via a Kafka event, with no direct synchronous calls between Orders, Inventory, and Notifications.

### Phase 3.1 — Static analysis (SonarCloud)

**Status:** Complete (2026-08-21). See [`docs/superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md`](superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md) and [`docs/superpowers/plans/2026-08-21-sonarcloud-static-analysis.md`](superpowers/plans/2026-08-21-sonarcloud-static-analysis.md) for the design and plan it was built from.

GitHub Actions job running SonarCloud analysis (bugs, code smells, vulnerabilities, JaCoCo coverage) for each of the 5 services on every PR, advisory-only — reports findings via SonarCloud's PR decoration, but is not a required status check yet.

Focus: closing the gap where static-analysis findings only surfaced ad hoc via local SonarLint, not reproducibly in CI (prompted by deprecated Jackson API usage slipping through Phase 3's review).

**Done when:** a PR triggers a `sonar` check for each of the 5 services, results (including coverage) are visible on SonarCloud's dashboard and as a PR comment, and none of it blocks merging.

### Phase 4 — Payments moves to asynchronous messaging

**Status:** Complete (2026-08-21). See [`docs/superpowers/specs/2026-08-21-phase4-payments-messaging-design.md`](superpowers/specs/2026-08-21-phase4-payments-messaging-design.md) and [`docs/superpowers/plans/2026-08-21-phase4-payments-messaging.md`](superpowers/plans/2026-08-21-phase4-payments-messaging.md) for the design and plan it was built from.

`orders` → `payments` moves from the synchronous REST/Feign call (unchanged since Phase 1) to a RabbitMQ command/reply, mirroring the pattern Phase 3 established for Inventory.

Includes compensation: if payment is declined after inventory was already reserved for that order, Orders commands Inventory to release the reservation — a saga-style compensating action, not a two-phase commit.

Focus: applying the RabbitMQ command pattern to an existing, already-tested critical path rather than a new integration, plus saga compensation.

**Done when:** creating an order no longer makes any synchronous call to `payments`; a payment decline after a successful inventory reservation results in that reservation being released; and `TD-1` is resolved, since RabbitMQ's retry/redelivery replaces the unretried synchronous call that used to leave orders stuck.

### Phase 5 — Kubernetes orchestration

Migrate from docker-compose to K8s manifests (Deployments, Services, ConfigMaps/Secrets), running locally via Minikube or Kind.

Focus: basic K8s objects, service discovery, scaling.

**Done when:** all services and brokers come up on a local cluster (Minikube/Kind) via versioned manifests, with the same end-to-end flow from Phase 4 working.

### Phase 6 — API Gateway and Kubernetes Ingress

An **API Gateway** (deferred from Phase 1 — see decision below) is introduced here, pairing naturally with the Kubernetes Ingress concept from Phase 5. Routing only in this phase — it proxies directly to each service, with no response composition yet (that's Phase 8's BFF). This is an intentional, temporary state: it partially resolves `TD-3` (services stop publishing ports directly to the host) but doesn't fully close it until Phase 8 makes the Gateway the only way in.

Focus: gateway/ingress as the external entry point, hiding internal service topology from any future client.

**Done when:** all services are reachable only through the Gateway (no more direct host port publishing), routed via K8s Ingress, with the same end-to-end flow from Phase 5 working.

### Phase 7 — Observability and resilience (optional, but recommended for portfolio)

Distributed tracing (Zipkin/Jaeger), circuit breaker (Resilience4j), health checks, centralized logging.

Focus: what turns a learning project into something defensible in a technical interview.

**Done when:** distributed tracing is visible across at least 2 services, health checks are exposed, and a circuit breaker is configured on at least one synchronous call between services.

### Phase 8 — Backend For Frontend (BFF)

A dedicated composition layer, sitting behind the Gateway (Phase 6) and in front of every microservice: `Client → Gateway → BFF → microservices`. Enforces a strict rule — the Gateway routes only to the BFF, and the BFF is the only thing allowed to call the domain services directly. No microservice is reachable from outside except through this chain.

Built primarily for portfolio value: no phase in this roadmap plans an actual web/mobile client, so the BFF's justification is demonstrating the pattern correctly, not serving a real frontend's needs. See the brainstorming session discussion from 2026-08-13/14 for the full reasoning (Gateway-only vs. hybrid vs. strict BFF trade-offs).

Focus: request composition/aggregation across services (e.g., a single "order details" view combining Orders' status, Inventory's reservation, and Payments' charge outcome), as an alternative to a CQRS-style read model.

**Done when:** no microservice is reachable except through the BFF (the Gateway's only route is to the BFF), and at least one composed endpoint aggregates data from 2+ microservices into a single response. This fully resolves `TD-3`.

### Phase 9 — Real cloud infrastructure via Terraform (optional, requires a cloud account)

Provision an actual managed Kubernetes cluster in the cloud using Terraform, then deploy the same manifests from Phase 5 (plus the Phase 6 Gateway and Phase 8 BFF) onto it — reinforcing the split between "what provisions the cluster" (Terraform) and "what runs inside it" (Kubernetes manifests/Helm).

Focus: Infrastructure as Code, cloud resource lifecycle (`plan` / `apply` / `destroy`), treating cluster infrastructure and workload deployment as separate concerns.

Provider choice and cost are a decision for when this phase is actually planned, not now.

Sequenced last, after the BFF, because it's the only phase gated on an external dependency (a cloud account) outside the roadmap's control — if it's ever skipped or delayed, every other phase (including the BFF) will still have landed.

**Done when:** `terraform apply` provisions a working managed Kubernetes cluster from scratch, the Phase 5/6/8 manifests deploy successfully onto it, and `terraform destroy` tears it down cleanly with no orphaned resources.

## Decision log

Known limitations are tracked in [`docs/decision-log/tech-debts.md`](decision-log/tech-debts.md); deliberate choices not to build or adopt something are tracked in [`docs/decision-log/rejected-approaches.md`](decision-log/rejected-approaches.md) — not here. Check both before starting each new phase.

## Deferred decisions

Decisions tied to *when* something joins the roadmap, not *whether* — these are on their way in, just not yet:

- **API Gateway:** not included in Phase 1. With no external client yet (everything is called directly via tests/Postman), a gateway wouldn't add learning value this early. Introduced in Phase 6, pairing with Kubernetes Ingress (Phase 5).
- **BFF (Backend For Frontend):** deferred further still, to Phase 8, after the Gateway. See Phase 8's own description for the full reasoning — a strict `Gateway → BFF → microservices` layering, adopted for portfolio demonstration value even though no phase in this roadmap plans a real client for it to serve.

Decisions to *not* build or adopt something at all (no planned phase) are tracked in [`docs/decision-log/rejected-approaches.md`](decision-log/rejected-approaches.md) instead.

## Next step

Phase 1 is complete — see [`docs/superpowers/specs/2026-07-31-phase1-foundation-design.md`](superpowers/specs/2026-07-31-phase1-foundation-design.md) and [`docs/superpowers/plans/2026-07-31-phase1-foundation.md`](superpowers/plans/2026-07-31-phase1-foundation.md) for the design and plan it was built from. Phase 1.1 (CI test gate) is also complete — see [`docs/superpowers/specs/2026-08-11-ci-pipeline-design.md`](superpowers/specs/2026-08-11-ci-pipeline-design.md) and [`docs/superpowers/plans/2026-08-11-ci-pipeline.md`](superpowers/plans/2026-08-11-ci-pipeline.md). Phase 2 (containerization) is also complete — see [`docs/superpowers/specs/2026-08-11-phase2-containerization-design.md`](superpowers/specs/2026-08-11-phase2-containerization-design.md) and [`docs/superpowers/plans/2026-08-11-phase2-containerization.md`](superpowers/plans/2026-08-11-phase2-containerization.md). Phase 3 (asynchronous messaging — inventory reservation via RabbitMQ, notifications via Kafka) is also complete — see [`docs/superpowers/specs/2026-08-14-phase3-messaging-design.md`](superpowers/specs/2026-08-14-phase3-messaging-design.md) and [`docs/superpowers/plans/2026-08-14-phase3-messaging.md`](superpowers/plans/2026-08-14-phase3-messaging.md). Phase 3.1 (static analysis via SonarCloud) is also complete — see [`docs/superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md`](superpowers/specs/2026-08-21-sonarcloud-static-analysis-design.md) and [`docs/superpowers/plans/2026-08-21-sonarcloud-static-analysis.md`](superpowers/plans/2026-08-21-sonarcloud-static-analysis.md). Phase 4 (payments moves to asynchronous messaging) is also complete — see [`docs/superpowers/specs/2026-08-21-phase4-payments-messaging-design.md`](superpowers/specs/2026-08-21-phase4-payments-messaging-design.md) and [`docs/superpowers/plans/2026-08-21-phase4-payments-messaging.md`](superpowers/plans/2026-08-21-phase4-payments-messaging.md). Phase 5 (Kubernetes orchestration) is next. See [`docs/architecture.md`](architecture.md) for how this and future phases fit into the system's overall topology.
