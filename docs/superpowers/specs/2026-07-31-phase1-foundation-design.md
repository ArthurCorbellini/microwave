# Phase 1 — Microservices Foundation (Synchronous REST)

**Date:** 2026-07-31
**Status:** approved, ready for implementation planning
**Related:** [Roadmap overview](../../roadmap.md) · [Tech debt](../../tech-debt.md)

## Goal

Stand up three independent Spring Boot services that together support creating an order end-to-end over synchronous REST, establishing service boundaries, API contracts, and a "database per service" model — without messaging, containers, or orchestration yet.

**Done when:** all 3 services run locally, each with its own database, and it's possible to create an order end-to-end (catalog → order → simulated payment) via REST calls, with automated tests covering the main flow.

## Repository layout

Mono-repo, one Maven module per service, no parent POM (each service builds independently):

```
microwave/
├── services/
│   ├── catalog/
│   ├── orders/
│   └── payments/
└── docs/
```

## Stack (all services)

- Java 25 (LTS)
- Spring Boot 4.0.7 (Spring Web, Spring Data JPA, Validation) — pinned to 4.0.x rather than 4.1.x because `orders` needs Spring Cloud OpenFeign, and Spring Cloud 2025.1.2 (the latest release train with confirmed Spring Boot compatibility as of this writing) targets Spring Boot 4.0.7
- Spring Cloud 2025.1.2 (`orders` only, for OpenFeign)
- Maven
- PostgreSQL — one database per service
- OpenFeign — declarative REST client, used only by `orders` to call `catalog` and `payments`
- Service addressing: fixed URLs via config (e.g. `catalog.service.url` in `application.yml`), no service discovery (Eureka) in this phase

## Services

### catalog (port 8081, `catalog_db`)

`Product { id, name, description, price: BigDecimal }`

- `GET /products` — list products
- `GET /products/{id}` — product detail (called by `orders` via Feign)
- `POST /products` — create a product (used to seed test data)

### payments (port 8082, `payments_db`)

`Payment { id, orderId, amount: BigDecimal, status: APPROVED | REJECTED }`

- `POST /payments` — receives `{ orderId, amount }`, simulates processing, returns the result. Simulation rule: approves when `amount <= 10000`, rejects otherwise — a deterministic, testable rule standing in for a real payment gateway.
- `GET /payments/{orderId}` — look up payment status

### orders (port 8083, `orders_db`) — the orchestrator

`Order { id, productId, quantity, totalAmount: BigDecimal, status: CREATED | CONFIRMED | REJECTED }`

- `POST /orders` — receives `{ productId, quantity }`, orchestrates the flow below
- `GET /orders/{id}` — order detail
- `GET /orders` — list orders

`catalog` and `payments` are unaware of `orders` or each other — they only respond to what they're asked. `orders` is the only service with orchestration responsibility in this phase.

## `POST /orders` flow

| Step | Action | On failure |
|---|---|---|
| 1 | Validate request (`quantity > 0`, `productId` present) | `400`, no order created |
| 2 | `GET /products/{productId}` on `catalog` via Feign | Product not found → `404`. `catalog` unreachable → `503`. No order created in either case |
| 3 | Compute `totalAmount = price * quantity` | — |
| 4 | Persist `Order` with `status = CREATED` | — |
| 5 | `POST /payments { orderId, amount }` on `payments` via Feign | See 6a–6c |
| 6a | `payments` responds `APPROVED` | Update `status = CONFIRMED`, return `201` with the order |
| 6b | `payments` responds `REJECTED` | Update `status = REJECTED`, return `201` with the order — this is a valid business outcome, not an HTTP error |
| 6c | `payments` unreachable / timeout | Order stays persisted with `status = CREATED` (not rolled back); API returns `503` |

Case 6c is a known, deliberate limitation of this phase (no saga/compensation over synchronous calls) — tracked as [TD-1 in tech-debt.md](../../tech-debt.md#td-1--orders-can-get-stuck-in-created-if-payments-is-unreachable) and expected to be addressed by Phase 3's messaging design.

## Error handling

Each service uses `@ControllerAdvice` + `@ExceptionHandler` for a consistent error body: `timestamp, status, error, message, path`. In `orders`, Feign failures (404 from `catalog`, connection errors) are caught and translated into this same error format rather than leaking raw Feign exceptions.

## Testing strategy

- JUnit 5 across all services
- Testcontainers for integration tests that hit a real Postgres instance per service
- MockMvc for controller-layer tests
- `orders` depends on `catalog` and `payments` over HTTP; its tests stub those Feign clients (WireMock or `@MockBean`) rather than requiring the other two services to be running, so each service is testable in isolation
- Coverage target: the main end-to-end flow (successful order, product not found, payment rejected, payment service unreachable) must be covered by automated tests — this is the phase's definition of done, not just a nice-to-have

## Out of scope for this phase

- Stock/inventory tracking (`catalog.Product` intentionally has no stock field — real inventory management is Phase 3's `inventory` service)
- Messaging (RabbitMQ/Kafka) — Phase 3
- Containers, Kubernetes — Phases 2 and 4
- API Gateway, service discovery — deferred to Phase 4 (see roadmap overview)
- Authentication/authorization — not addressed in the roadmap yet; revisit if needed before treating the project as portfolio-complete
