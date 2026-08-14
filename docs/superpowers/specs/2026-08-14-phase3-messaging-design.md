# Asynchronous Messaging (Inventory + Notifications) — Design

**Date:** 2026-08-14
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 3 — Asynchronous messaging (hybrid)

## Purpose

Introduce two new services — `inventory` and `notifications` — and connect them to `orders` via messaging instead of REST: RabbitMQ for a point-to-point stock-reservation command, Kafka for a domain event consumed by Notifications. `orders` → `payments` stays synchronous REST, unchanged since Phase 1 — its migration to RabbitMQ is Phase 4's scope, not this one.

## Motivation

Phase 1/2 only exercise synchronous REST between services. This phase introduces the two messaging patterns the project's roadmap deliberately keeps side by side (hybrid, not pure EDA) — task-queue/async-RPC via RabbitMQ vs. decoupled domain events via Kafka — plus the concerns that come with them for the first time: eventual consistency, idempotency under at-least-once delivery, and dead-lettering.

## Scope

**In scope:**
- New services `inventory` and `notifications`, each with its own Postgres database, following the package layout and testing shape in `docs/conventions.md`.
- `orders` → `inventory`: a `ReserveStock` command over RabbitMQ, with an `InventoryReserved` reply.
- `orders` → Kafka → `notifications`: an `OrderCreated` domain event.
- `POST /orders` responds `201`/`CREATED` immediately, without waiting for the reservation or the payment outcome — a behavior change from Phase 1/2's blocking response (see "Order creation flow" below).
- Idempotency for both the RabbitMQ command/reply and the Kafka event, since both give only at-least-once delivery.
- Retry with backoff + dead-lettering for messages that repeatedly fail to process.
- New read endpoints on `inventory` and `notifications` so their side of an order's outcome can be inspected and asserted in tests.

**Out of scope (explicitly deferred):**
- `orders` → `payments` moving to RabbitMQ — Phase 4.
- Compensation (releasing a reservation if payment is later declined) — Phase 4's scope, since it only exists once `payments` is also commanded asynchronously. **Known limitation accepted for this phase:** if payment is declined after a successful reservation, that reservation is never released — the stock stays committed until Phase 4 introduces the compensating `ReleaseStock` command. This becomes a tracked tech-debt entry when Phase 3 is actually implemented, not before (tech-debt entries land with the PR that introduces the gap — see `docs/decision-log/tech-debts.md`'s existing entries for the pattern).
- Monitoring/alerting on dead-letter queues, or automated reprocessing — Phase 7. Same rule applies: tracked as tech debt at implementation time.
- A composed view of an order across Orders/Inventory/Payments in a single response — that's Phase 8's BFF. `GET /orders/{id}` in this phase reflects only what `orders` itself knows.
- Any change to `orders` → `catalog` — stays synchronous REST throughout every phase.

## Design

### Order status model

`Order` stays minimal — `CREATED` / `CONFIRMED` / `REJECTED` — with no added intermediate status and no `rejectionReason` field. Each service that participates owns its own detail instead of `orders` duplicating it:

- `inventory` owns `Reservation{id, orderId, productId, quantity, status: RESERVED|RELEASED, createdAt}`.
- `payments` already owns its own payment record and status (Phase 1).

This was a deliberate choice (brainstorming session, 2026-08-13/14) after considering and rejecting: a 4th `INVENTORY_RESERVED` status value, a flattened `OUT_OF_STOCK`/`PAYMENT_DECLINED` status enum, a generic status-detail field mixing progress and rejection cause, and a sealed-interface domain model (correct, but disproportionately expensive to persist via JPA for two known causes). Composing these per-service details into one client-facing view is Phase 8's job (BFF), not this phase's.

### Order creation flow

```
POST /orders
  → orders calls catalog synchronously (unchanged) to price the item
  → orders persists Order{status: CREATED}
  → orders responds 201 immediately — client does not wait for reservation or payment
  → orders publishes OrderCreated to Kafka
  → orders sends ReserveStock to inventory (RabbitMQ)

(in the background, from orders' RabbitMQ listener — not the original request thread)
  → inventory replies InventoryReserved
  → if reserved=false: Order → REJECTED (client learns why by querying inventory, not orders)
  → if reserved=true: orders calls payments synchronously (REST, unchanged), same as Phase 1
      → approved: Order → CONFIRMED
      → declined: Order → REJECTED (reservation stays committed — see "Known limitation" above)

Client discovers the outcome via GET /orders/{id}, polling as needed.
```

Rejected alternative: block the original `POST /orders` request until the full reservation+payment sequence resolves (sync-over-async via RabbitMQ's RPC/reply-to pattern). Technically valid and used in production elsewhere, but it defeats this phase's purpose — the client would never observe anything different from Phase 1/2, and Phase 4 would then have to change both the transport (REST→RabbitMQ for payments) and the response-timing model at once, instead of just the transport.

Order status transitions are guarded by JPA optimistic locking (`@Version` on `Order`), so a redelivered `InventoryReserved` reply is a no-op once the order has already left `CREATED` — this is `orders`' own idempotency guard on the reply side.

### RabbitMQ topology

Each service owns the exchange that routes messages *to* it, mirroring "database per service":

| Exchange (type: `direct`) | Queue | Owner | Purpose |
|---|---|---|---|
| `inventory.exchange` | `inventory.reserve-stock.queue` | `inventory` | receives `ReserveStock` commands |
| `orders.exchange` | `orders.inventory-reply.queue` | `orders` | receives `InventoryReserved` replies |

Message schemas:

```
ReserveStock (orders → inventory)
  orderId: Long
  productId: Long
  quantity: int

InventoryReserved (inventory → orders)
  orderId: Long
  reserved: boolean
  reason: String (optional — e.g. "OUT_OF_STOCK"; used only for orders' own
                   decision logic/logging, never persisted onto Order — see
                   "Order status model")
```

Named queues per command type, not one shared "inventory commands" queue — Phase 4 adds a second command (`ReleaseStock`) to `inventory`, and each command type should be independently retryable/observable rather than sharing a queue.

### Kafka topology

- **Topic:** `orders.order-created` — prefixed by owning service, so future event topics (e.g. a Phase 4 payment-outcome event) don't collide.
- **Key:** `orderId` — keeps every message for a given order on the same partition.
- **Consumer group:** `notifications-service`.

```
OrderCreated (orders → Kafka)
  orderId: Long
  productId: Long
  quantity: int
  totalAmount: BigDecimal
  createdAt: Instant
```

### `inventory`'s stock model

```
Stock{productId, availableQuantity}
Reservation{id, orderId, productId, quantity, status: RESERVED|RELEASED, createdAt}
```

`inventory` does not call `catalog` to validate `productId` — `orders` already validated it synchronously before ever sending the command, and re-validating downstream would reintroduce the exact synchronous coupling this phase removes. An unknown `productId` (no `Stock` row) is treated the same as insufficient stock. Initial `Stock` rows are seeded for local/dev use, following whatever pattern `catalog` already uses for its own demo products.

### `notifications`'s behavior

```
NotificationLog{id, orderId, type, message, sentAt}
```

Simulated, same spirit as `payments` since Phase 1 — no real email/SMS integration. On consuming `OrderCreated`, `notifications` writes a `NotificationLog` row (message templated from the event) and logs it. `type` is included now (even though only one event exists this phase) so Phase 4's additional events don't require a schema change later.

### Idempotency (at-least-once delivery, both transports)

- **`inventory`** (`ReserveStock`): before processing, check whether a `Reservation` already exists for `orderId`. If it does, skip reprocessing and resend the same reply — the `Reservation` row itself is the dedupe ledger, no separate table needed.
- **`orders`** (`InventoryReserved` reply): only act if the order's current status is still `CREATED` (enforced via optimistic locking — see "Order creation flow").
- **`notifications`** (`OrderCreated`): unique constraint on `(orderId, type)` in `NotificationLog`; skip if a row already exists.

### Retry and dead-lettering

Both transports: 3 attempts with exponential backoff, then dead-lettered instead of looping forever or being silently dropped.

- **RabbitMQ:** Spring AMQP `RetryTemplate` for in-process retries, `RepublishMessageRecoverer` to publish to a dead-letter exchange on exhaustion.
- **Kafka:** Spring Kafka `DefaultErrorHandler` with a configured back-off, `DeadLetterPublishingRecoverer` publishing to a `.DLT` topic on exhaustion.

No monitoring or alerting on either dead-letter destination in this phase — see "Out of scope."

### New read endpoints

- `GET /inventory/reservations/{orderId}` — `inventory`'s own reservation record for that order.
- `GET /notifications/{orderId}` — `notifications`'s own log for that order.

Needed for two independent reasons that happen to coincide: the "G puro" ownership model means this detail only exists in these services, and integration tests need some way to assert the async flow actually happened.

## Documentation updates (at implementation time)

- `docs/conventions.md`: document the RabbitMQ/Kafka topology conventions above (exchange-per-owning-service, queue-per-command-type, topic-naming) as shared patterns for future services.
- `docs/decision-log/tech-debts.md`: two new entries, added in the same PR as the code that introduces each gap (not before) —
  - reservations aren't released on a post-reservation payment decline (planned resolution: Phase 4).
  - dead-letter queues/topics have no monitoring (planned resolution: Phase 7).
- `docs/roadmap.md`: mark Phase 3 complete once done, linking to this spec and its plan.
- `docs/architecture.md`: update the "Current architecture" diagram to include `inventory`/`notifications` and the new RabbitMQ/Kafka edges.

## Testing

Extends the existing shape from `docs/conventions.md` rather than introducing a new one:

- Unit tests for `inventory`/`notifications` service-layer logic (reservation decision, idempotency checks).
- `MockMvc` for the new `GET /inventory/reservations/{orderId}` and `GET /notifications/{orderId}` controllers.
- Testcontainers integration tests against real Postgres, extended with Testcontainers' RabbitMQ and Kafka modules — consumers/producers are tested against real brokers, not mocked, consistent with how Postgres is already tested for real via `@ServiceConnection`.
- An end-to-end test for the full `orders` → `inventory` → (`orders` → `payments`) → status-settles flow, asserting eventual consistency by polling `GET /orders/{id}` rather than expecting an immediate result.
- A duplicate-delivery test per idempotent consumer (redeliver the same command/event, assert no double-processing).
- A dead-letter test per consumer (a message that always fails processing ends up in the dead-letter destination after 3 attempts, not retried forever).

## Error handling

Beyond the idempotency/retry/dead-lettering already covered above: `TD-1` (orders stuck in `CREATED` if `payments` is unreachable) is explicitly **not** resolved by this phase — `payments` stays synchronous REST here, so the same failure mode from Phase 1 still applies when `orders` calls it from the reservation-reply listener. Resolution stays tracked against Phase 4, per `docs/decision-log/tech-debts.md`.
