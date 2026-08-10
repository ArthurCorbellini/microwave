# Rejected Approaches

Living list of approaches that were considered and declined without ever being implemented, so future sessions (human or Claude) don't re-propose them without first seeing why they were rejected. Each entry states what was considered, why it was rejected, and what would justify revisiting it.

This is different from the decision log's other categories:

- `docs/decision-log/tech-debts.md` tracks limitations *in the current, accepted design* — something that exists and works, just imperfectly. `docs/decision-log/rejected-approaches.md` tracks things that are **not** part of the current design at all.
- `docs/decision-log/failed-approaches.md` covers the same "not part of the design" territory, but reached by actually implementing something and watching it fail, with concrete evidence. `docs/decision-log/rejected-approaches.md`'s entries were evaluated and declined by reasoning alone — no code was ever written.

## Entry template

New entries should follow this structure:

```markdown
### RA-N — <short title>

**Considered in:** <phase/context>
**Where:** <affected service/component>

<1-2 sentences: what was considered>

**Why it was rejected:** <the reasoning>

**Revisit if:** <the condition that would justify building/adopting it>
```

## Entries

### RA-1 — Orders support a single product only, no cart

**Considered in:** Phase 1
**Where:** `orders` service, `Order` entity and `POST /orders`

`Order` models one product per order (`productId, quantity`), not a list of items. There's no shopping-cart concept (adding/removing items pre-checkout), which in turn would require a notion of user/session that the roadmap doesn't have yet.

**Why it was rejected:** deliberate scope decision — the project's goal is learning microservices/messaging/containers/orchestration, not building a feature-complete e-commerce domain. Multi-item orders and/or a real cart are both viable extensions, but neither was worth the added scope (cart specifically pulls in user/session/auth, which is unplanned) this early.

**Revisit if:** the project introduces users, or a phase's design would clearly benefit from multi-item orders (e.g., a richer example for Phase 3's messaging flows). Until then, single-product orders stay as-is.

### RA-2 — MapStruct not adopted for entity↔DTO mapping

**Considered in:** Phase 1 (post-merge architecture review)
**Where:** `catalog`, `payments`, `orders` — all Response DTOs (`ProductResponse`, `PaymentResponse`, `OrderResponse`)

Entity↔DTO mapping is done by hand-written static `from()` factory methods on the DTO records, not MapStruct. Every current mapping is a trivial 1:1 field copy (entity getter → record field), so the manual version fails to compile if a field is missed, needs no annotation processor, and doesn't require entities to expose a no-args constructor + setters.

**Why it was rejected:** MapStruct's real value (nested objects, field renames, type conversion) isn't exercised by any current DTO, and its default `unmappedTargetPolicy` is `WARN`, not a compile error — a looser safety net than the manual `from()` already gives for free.

**Revisit if:** a future phase introduces DTOs complex enough to justify it (nested objects, renamed fields, type conversions).
