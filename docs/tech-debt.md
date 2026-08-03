# Tech Debt

Living list of known limitations and deliberate trade-offs, tracked across phases so future sessions (human or Claude) don't rediscover them from scratch. Each entry states what the gap is, why it exists, and when/how it's expected to be addressed.

## Open

### TD-1 — Orders can get stuck in `CREATED` if `payments` is unreachable

**Introduced in:** Phase 1
**Where:** `orders` service, `POST /orders` flow

If the call to `payments` fails for a technical reason (service down, timeout — not a business rejection), the order is already persisted with `status=CREATED` and is never moved to `CONFIRMED` or `REJECTED`. There's no retry, no saga, no compensation. The client gets a `503`, but the order row is left in an unresolved state with no automatic follow-up.

**Why it exists:** Phase 1 is scoped to synchronous REST only, deliberately, to focus on service boundaries and API contracts before introducing messaging.

**Planned resolution:** Phase 3 (asynchronous messaging) replaces this synchronous chain with RabbitMQ commands / Kafka events, which naturally supports retry and eventual consistency. Revisit this specific flow when designing Phase 3's order-creation sequence.

### TD-2 — Orders support a single product only, no cart

**Introduced in:** Phase 1
**Where:** `orders` service, `Order` entity and `POST /orders`

`Order` models one product per order (`productId, quantity`), not a list of items. There's no shopping-cart concept (adding/removing items pre-checkout), which in turn would require a notion of user/session that the roadmap doesn't have yet.

**Why it exists:** deliberate scope decision — the project's goal is learning microservices/messaging/containers/orchestration, not building a feature-complete e-commerce domain. Multi-item orders and/or a real cart are both viable extensions, but neither was worth the added scope (cart specifically pulls in user/session/auth, which is unplanned) this early.

**Planned resolution:** none scheduled. Revisit if/when the project introduces users, or if a phase's design would clearly benefit from multi-item orders (e.g., a richer example for Phase 3's messaging flows). Until then, single-product orders stay as-is.

## Resolved

_(none yet)_
