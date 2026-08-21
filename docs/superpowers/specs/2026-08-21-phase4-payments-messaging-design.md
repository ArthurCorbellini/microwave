# Payments Moves to Asynchronous Messaging — Design

**Date:** 2026-08-21
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 4 — Payments moves to asynchronous messaging

## Purpose

Replace the synchronous REST/Feign call from `orders` to `payments` with a RabbitMQ command/reply, mirroring the pattern Phase 3 established for `orders` → `inventory`. Add a compensating `ReleaseStock` command from `orders` to `inventory`, triggered when a payment is declined after a successful reservation.

## Motivation

`orders` → `payments` has stayed synchronous REST, unchanged since Phase 1, while Phase 3 already moved `orders` → `inventory` to RabbitMQ. This leaves two open gaps that only resolve once `payments` follows the same migration:

- `TD-1`: if `payments` is unreachable for a technical reason, the order is stranded in `CREATED` forever — no retry, no saga.
- `TD-6`: if `payments` declines after `inventory` already reserved stock, the reservation is never released.

## Scope

**In scope:**
- `orders` → `payments`: a `ChargePayment` command over RabbitMQ, with a `PaymentProcessed` reply — replacing the `PaymentsClient` Feign call entirely.
- `orders` → `inventory`: a `ReleaseStock` command over RabbitMQ (fire-and-forget, no reply), sent when a `PaymentProcessed` reply is declined.
- Idempotency for both new command/reply pairs, since RabbitMQ gives only at-least-once delivery — including adding a dedupe check to `payments`, which today has none.
- Retry with backoff + dead-lettering for the three new queues, same shape as Phase 3.
- Removing `POST /payments` (and its `PaymentRequest` DTO, and orders' `PaymentsClient`/WireMock contract test), now that nothing calls it synchronously.
- Extending the existing `orders` end-to-end test to cover the decline-with-compensation path.

**Out of scope (explicitly deferred):**
- Monitoring/alerting on dead-letter queues — still Phase 7 (`TD-7`, extended by this phase, not newly created).
- Resilience against the RabbitMQ broker itself being unreachable during publish — still Phase 7 (`TD-9`, extended by this phase, not newly created).
- `GET /payments/{orderId}` stays as-is — this phase only removes the write path.
- Any change to `orders` → `catalog` — stays synchronous REST throughout every phase.
- A composed cross-service view of an order's full outcome — Phase 8's BFF.

## Design

### Order status model

Unchanged from Phase 3: `Order` stays `CREATED` / `CONFIRMED` / `REJECTED`, no new intermediate status. `Order` remains in `CREATED` for the entire window between the `InventoryReserved` reply and the `PaymentProcessed` reply — exactly one of `CONFIRMED`/`REJECTED` is reached at the end of that window, never both — so the existing "only act if `status == CREATED`" guard (via `@Version` optimistic locking) protects both reply listeners without modification, the same way it already protects the single listener today.

### Order creation flow

```
POST /orders (unchanged)
  → orders persists Order{status: CREATED}, publishes OrderCreated (Kafka), sends ReserveStock (RabbitMQ)

InventoryReservedListener (orders — existing, reserved=true branch changes)
  → reserved=false: Order → REJECTED (unchanged)
  → reserved=true: sends ChargePayment (RabbitMQ) to payments — no longer calls payments via REST/Feign

ChargePaymentListener (payments — new, mirrors ReserveStockListener)
  → PaymentService.charge(...) — same domain logic as before (PaymentSimulator.decide), now
    guarded by a findByOrderId idempotency check (see "Idempotency" below)
  → replies PaymentProcessed (RabbitMQ)

PaymentProcessedListener (orders — new)
  → approved: Order → CONFIRMED
  → declined: Order → REJECTED, sends ReleaseStock (RabbitMQ, fire-and-forget) to inventory

ReleaseStockListener (inventory — new)
  → idempotent: if Reservation is already RELEASED, no-op
  → else: Stock.increase(quantity), Reservation → RELEASED
```

Client discovers the outcome via `GET /orders/{id}`, same polling model as Phase 3 — this phase changes the transport and adds compensation, not the client-facing contract.

### RabbitMQ topology

New exchange/queue additions, following the existing "exchange per owning service, queue per command type" convention:

| Exchange | Queue | Routing key | Owner |
|---|---|---|---|
| `payments.exchange` | `payments.charge-payment.queue` | `charge-payment` | `payments` |
| `orders.exchange` | `orders.payment-reply.queue` | `payment-processed` | `orders` |
| `inventory.exchange` | `inventory.release-stock.queue` | `release-stock` | `inventory` |

Each queue gets its own DLX/DLQ pair (`<service>.dlx` / `<service>.<command>.dlq`), same retry policy already documented in `docs/conventions.md` (3 attempts, exponential backoff, 500ms initial ×2.0, `RepublishMessageRecoverer` on exhaustion). `payments.exchange` is new — declared by `payments` (owner) and defensively redeclared by `orders` (publisher), mirroring how `orders` already defensively redeclares `inventory.exchange`.

Message schemas:

```
ChargePayment (orders → payments)
  orderId: Long
  amount: BigDecimal

PaymentProcessed (payments → orders)
  orderId: Long
  approved: boolean
  reason: String (optional — mirrors InventoryReservedReply's "reason", same rule:
                   used only for orders' own decision logic/logging, never persisted onto Order)

ReleaseStock (orders → inventory)
  orderId: Long
```

`ReleaseStock` only carries `orderId`, not `productId`/`quantity` — `inventory` already has that data on the existing `Reservation` row for that `orderId`, no need to pass it again.

### Idempotency (at-least-once delivery)

- **`payments`** (`ChargePayment`): before processing, check whether a `Payment` already exists for `orderId` (`findByOrderId`) — same pattern `inventory`'s `ReservationService.reserve` already uses. This closes a gap: `payments` currently has no such check and no unique constraint on `Payment.orderId`. Both are added as part of this phase — the constraint mirrors `Reservation.orderId`'s existing unique constraint.
- **`orders`** (`PaymentProcessed` reply): only act if the order's current status is still `CREATED` (existing optimistic-locking guard, unchanged — see "Order status model" above).
- **`inventory`** (`ReleaseStock`): before processing, check the `Reservation`'s current status — if already `RELEASED`, skip; the `Reservation` row is the dedupe ledger, same role it already plays for `ReserveStock`.

### Payments REST surface

`POST /payments` is removed, along with `PaymentRequest` and `orders`' `PaymentsClient` (Feign) and its WireMock contract test (`PaymentsClientIT`) — nothing calls it synchronously anymore, and leaving it running risked creating `Payment` rows disconnected from any real order/reservation state. `GET /payments/{orderId}` and `PaymentResponse` stay unchanged, preserving parity with `inventory`'s and `notifications`' existing read-only inspection endpoints. `PaymentService.charge(...)` keeps the same signature and logic; only its caller changes, from the controller to the new `ChargePaymentListener`.

### Retry and dead-lettering

Same shape as Phase 3, applied to the three new queues: 3 attempts with exponential backoff via Spring AMQP's `RetryInterceptorBuilder`-based stateless retry interceptor, then `RepublishMessageRecoverer` to the owning service's DLX. No monitoring or alerting on any dead-letter destination in this phase — see "Tech debt updates" below.

## Tech debt updates (at implementation time)

- **`TD-1`** (orders stuck in `CREATED` if `payments` unreachable) — **resolved**. With `ChargePayment` over RabbitMQ, an unreachable `payments` no longer strands the order: the command waits in `payments.charge-payment.queue` until `payments` is back up and consumes it, instead of failing an unretried synchronous call.
- **`TD-6`** (reservation not released on post-reservation decline) — **resolved** by the new `ReleaseStock` command.
- **`TD-7`** (dead-letter queues exist, nothing watches them) — **extended, not superseded**: its "Where" list grows to include `payments.charge-payment.dlq`, `orders.payment-reply.dlq`, and `inventory.release-stock.dlq`. A `ReleaseStock` message that exhausts retries and lands in `inventory.release-stock.dlq` is exactly this same unmonitored-DLQ gap, not a new failure mode — still resolved by Phase 7's monitoring/alerting work, not before.
- **`TD-9`** (order creation isn't resilient to brokers being unreachable during publish) — **extended, not superseded**: its "Where" list grows to include the `ChargePayment` and `ReleaseStock` send sites in `orders`, which inherit the same unhandled-publish-failure gap `ReserveStock` already has. Still deferred to Phase 7.

## Documentation updates (at implementation time)

- `docs/conventions.md`: no new pattern introduced — the existing RabbitMQ topology conventions (exchange-per-owning-service, queue-per-command-type, retry/DLQ shape) already cover this phase's additions verbatim.
- `docs/decision-log/tech-debts.md`: update `TD-1` (move to `## Resolved`), `TD-6` (move to `## Resolved`), `TD-7` and `TD-9` (extend "Where" sections) — in the same PR as the code that introduces each change, not before.
- `docs/roadmap.md`: mark Phase 4 complete once done, linking to this spec and its plan.
- `docs/architecture.md`: update the "Current architecture" diagram — `orders --REST, sync--> payments` becomes `orders --RabbitMQ command/reply--> payments`; this also finally matches the "Target architecture" diagram's `Orders -->|"RabbitMQ command/reply"| Payments` edge, which was already drawn ahead of time and annotated "Phase 4."

## Testing

Extends the existing shape from `docs/conventions.md`, same as Phase 3:

- Unit tests for the new `PaymentProcessedListener`/`ChargePaymentListener`/`ReleaseStockListener` logic and the changed `InventoryReservedListener` branch.
- Testcontainers integration tests against real RabbitMQ for all three new queues (consumers/producers tested against a real broker, not mocked).
- A duplicate-delivery test per new idempotent consumer (`payments`' `ChargePayment` handler, `orders`' `PaymentProcessed` handler, `inventory`'s `ReleaseStock` handler).
- A dead-letter test per new consumer (a message that always fails processing ends up in the dead-letter destination after 3 attempts).
- The existing `orders` end-to-end test (eventual consistency via polling `GET /orders/{id}`) extends to cover the decline-with-compensation path: reserve succeeds, payment declines, `Order` reaches `REJECTED`, and `GET /inventory/reservations/{orderId}` shows `RELEASED`.
- `PaymentsClientIT` (orders' WireMock contract test for the old Feign client) is deleted — there's no synchronous contract left to test.

## Error handling

Beyond idempotency/retry/dead-lettering already covered above: this phase deliberately does not add publish-failure handling for `ChargePayment` or `ReleaseStock` (broker-unreachable-during-send) — that's `TD-9`'s scope, deferred to Phase 7 same as `ReserveStock`'s existing gap. It also does not add monitoring on any of the three new dead-letter destinations — that's `TD-7`'s scope, same deferral.
