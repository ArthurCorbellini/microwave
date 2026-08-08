# Tech Debt

Living list of known limitations — shortcuts whose cost of fixing grows the longer they're left unaddressed — tracked across phases so future sessions (human or Claude) don't rediscover them from scratch. Each entry states what the gap is, why it exists, and when/how it's expected to be addressed.

Deliberate choices *not* to build or adopt something (no compounding cost either way) are tracked separately in [`docs/scope-decisions.md`](scope-decisions.md).

## Open

### TD-1 — Orders can get stuck in `CREATED` if `payments` is unreachable

**Introduced in:** Phase 1
**Where:** `orders` service, `POST /orders` flow

If the call to `payments` fails for a technical reason (service down, timeout — not a business rejection), the order is already persisted with `status=CREATED` and is never moved to `CONFIRMED` or `REJECTED`. There's no retry, no saga, no compensation. The client gets a `503`, but the order row is left in an unresolved state with no automatic follow-up.

**Why it exists:** Phase 1 is scoped to synchronous REST only, deliberately, to focus on service boundaries and API contracts before introducing messaging.

**Planned resolution:** Phase 3 (asynchronous messaging) replaces this synchronous chain with RabbitMQ commands / Kafka events, which naturally supports retry and eventual consistency. Revisit this specific flow when designing Phase 3's order-creation sequence.

## Resolved

_(none yet)_
