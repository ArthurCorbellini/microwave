# Scope Decisions

Living list of things deliberately **not** built or adopted, so future sessions (human or Claude) don't re-propose them without first seeing why they were left out. Each entry states what was considered, why it was left out, and what would justify revisiting it.

This is different from the project's other decision logs:

- `docs/tech-debt.md` tracks real debt — a shortcut whose cost of fixing *grows* the longer it's left unaddressed. A scope decision has no such cost: not building the thing costs nothing extra later: if it's ever needed, it just gets built then.
- `docs/rejected-approaches.md` tracks approaches that were actually *tried in the code* and failed for a concrete, verifiable reason. A scope decision was never implemented — it was evaluated and declined before any code was written.
- `docs/roadmap.md`'s "Deferred decisions" section tracks decisions tied to the project's phase timeline (e.g. "introduced in Phase 4") — a scope decision has no planned phase; it's simply not part of the design unless requirements change.

### SD-1 — Orders support a single product only, no cart

**Considered in:** Phase 1
**Where:** `orders` service, `Order` entity and `POST /orders`

`Order` models one product per order (`productId, quantity`), not a list of items. There's no shopping-cart concept (adding/removing items pre-checkout), which in turn would require a notion of user/session that the roadmap doesn't have yet.

**Why it was left out:** deliberate scope decision — the project's goal is learning microservices/messaging/containers/orchestration, not building a feature-complete e-commerce domain. Multi-item orders and/or a real cart are both viable extensions, but neither was worth the added scope (cart specifically pulls in user/session/auth, which is unplanned) this early.

**Revisit if:** the project introduces users, or a phase's design would clearly benefit from multi-item orders (e.g., a richer example for Phase 3's messaging flows). Until then, single-product orders stay as-is.

### SD-2 — MapStruct not adopted for entity↔DTO mapping

**Considered in:** Phase 1 (post-merge architecture review)
**Where:** `catalog`, `payments`, `orders` — all Response DTOs (`ProductResponse`, `PaymentResponse`, `OrderResponse`)

Entity↔DTO mapping is done by hand-written static `from()` factory methods on the DTO records, not MapStruct. Every current mapping is a trivial 1:1 field copy (entity getter → record field), so the manual version fails to compile if a field is missed, needs no annotation processor, and doesn't require entities to expose a no-args constructor + setters.

**Why it was left out:** MapStruct's real value (nested objects, field renames, type conversion) isn't exercised by any current DTO, and its default `unmappedTargetPolicy` is `WARN`, not a compile error — a looser safety net than the manual `from()` already gives for free.

**Revisit if:** a future phase introduces DTOs complex enough to justify it (nested objects, renamed fields, type conversions).
