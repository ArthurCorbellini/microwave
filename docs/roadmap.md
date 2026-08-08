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

### Phase 2 — Containerization

Dockerfile per service + docker-compose orchestrating all services and databases.

Focus: packaging, networking between containers, configuration via environment variables.

**Done when:** the full Phase 1 flow comes up and works with just `docker-compose up`, with nothing run manually from the IDE.

### Phase 3 — Asynchronous messaging (hybrid)

New services: `inventory` and `notifications`.

- **RabbitMQ** for point-to-point commands (e.g., Orders → Inventory "reserve item"). This is a task-queue/async-RPC pattern, not EDA — the publisher knows who should process it.
- **Kafka** for domain events (e.g., `OrderCreated`, `PaymentApproved`), consumed by Notifications and future services. This is EDA (event-driven): publisher and consumer are decoupled.

**Explicit decision:** keep the hybrid on purpose (not moving to pure EDA), to compare both messaging patterns hands-on. See brainstorming session discussion from 2026-07-31.

Focus: distinguishing command queues from event streams, eventual consistency, idempotency.

**Done when:** creating an order triggers an inventory reservation via RabbitMQ and generates a notification via a Kafka event, with no direct synchronous calls between Orders, Inventory, and Notifications.

### Phase 4 — Kubernetes orchestration

Migrate from docker-compose to K8s manifests (Deployments, Services, ConfigMaps/Secrets), running locally via Minikube or Kind.

An **API Gateway** (deferred from Phase 1 — see decision below) is introduced around this phase, pairing naturally with the Kubernetes Ingress concept.

Focus: basic K8s objects, service discovery, scaling, gateway/ingress as the external entry point.

**Done when:** all services and brokers come up on a local cluster (Minikube/Kind) via versioned manifests, with the same end-to-end flow from Phase 3 working.

### Phase 5 — Observability and resilience (optional, but recommended for portfolio)

Distributed tracing (Zipkin/Jaeger), circuit breaker (Resilience4j), health checks, centralized logging.

Focus: what turns a learning project into something defensible in a technical interview.

**Done when:** distributed tracing is visible across at least 2 services, health checks are exposed, and a circuit breaker is configured on at least one synchronous call between services.

### Phase 6 — Real cloud infrastructure via Terraform (optional, requires a cloud account)

Provision an actual managed Kubernetes cluster in the cloud using Terraform, then deploy the same manifests from Phase 4 onto it — reinforcing the split between "what provisions the cluster" (Terraform) and "what runs inside it" (Kubernetes manifests/Helm).

Focus: Infrastructure as Code, cloud resource lifecycle (`plan` / `apply` / `destroy`), treating cluster infrastructure and workload deployment as separate concerns.

Provider choice and cost are a decision for when this phase is actually planned, not now.

**Done when:** `terraform apply` provisions a working managed Kubernetes cluster from scratch, the Phase 4 manifests deploy successfully onto it, and `terraform destroy` tears it down cleanly with no orphaned resources.

## Tech debt and scope decisions

Known limitations are tracked in [`docs/tech-debt.md`](tech-debt.md); deliberate choices not to build or adopt something are tracked in [`docs/scope-decisions.md`](scope-decisions.md) — not here. Check both before starting each new phase.

## Deferred decisions

Decisions tied to *when* something joins the roadmap, not *whether* — these are on their way in, just not yet:

- **API Gateway:** not included in Phase 1. With no external client yet (everything is called directly via tests/Postman), a gateway wouldn't add learning value this early. Introduced around Phase 4, where it connects naturally with Kubernetes Ingress.

Decisions to *not* build or adopt something at all (no planned phase) are tracked in [`docs/scope-decisions.md`](scope-decisions.md) instead.

## Next step

Phase 1 is complete — see [`docs/superpowers/specs/2026-07-31-phase1-foundation-design.md`](superpowers/specs/2026-07-31-phase1-foundation-design.md) and [`docs/superpowers/plans/2026-07-31-phase1-foundation.md`](superpowers/plans/2026-07-31-phase1-foundation.md) for the design and plan it was built from. Phase 2 (containerization) is next, and starts with its own brainstorm → spec → plan cycle.
