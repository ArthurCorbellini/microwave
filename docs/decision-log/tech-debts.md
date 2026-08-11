# Tech Debts

Living list of known limitations — shortcuts whose cost of fixing grows the longer they're left unaddressed — tracked across phases so future sessions (human or Claude) don't rediscover them from scratch. Each entry states what the gap is, why it exists, and when/how it's expected to be addressed.

This is different from the decision log's other categories:

- `docs/decision-log/rejected-approaches.md` and `docs/decision-log/failed-approaches.md` both track things that are **not** part of the current design at all — declined by reasoning alone, or actually tried and reverted, respectively. This file tracks limitations *in* the current, accepted design: something that exists and works, just imperfectly.

## Entry template

New entries go under `## Open` and should follow this structure:

```markdown
### TD-N — <short title>

**Introduced in:** <phase/context>
**Where:** <affected service/component>

<1-2 sentences: what the gap is>

**Why it exists:** <the reasoning/trade-off that produced this>

**Planned resolution:** <what will fix it, and when/under what condition>
```

When a limitation is actually fixed, move its entry under `## Resolved` and add a `**Resolved in:**` field noting when/how it was addressed.

## Open

### TD-1 — Orders can get stuck in `CREATED` if `payments` is unreachable

**Introduced in:** Phase 1
**Where:** `orders` service, `POST /orders` flow

If the call to `payments` fails for a technical reason (service down, timeout — not a business rejection), the order is already persisted with `status=CREATED` and is never moved to `CONFIRMED` or `REJECTED`. There's no retry, no saga, no compensation. The client gets a `503`, but the order row is left in an unresolved state with no automatic follow-up.

**Why it exists:** Phase 1 is scoped to synchronous REST only, deliberately, to focus on service boundaries and API contracts before introducing messaging.

**Planned resolution:** Phase 3 (asynchronous messaging) replaces this synchronous chain with RabbitMQ commands / Kafka events, which naturally supports retry and eventual consistency. Revisit this specific flow when designing Phase 3's order-creation sequence.

### TD-2 — Branch protection's required checks are hardcoded, not derived from the CI matrix

**Introduced in:** Phase 1.1
**Where:** `main` branch protection rule (GitHub repo settings, not a versioned file)

Branch protection on `main` requires 3 check contexts (`test (catalog)`, `test (orders)`, `test (payments)`) that are hardcoded strings in GitHub's branch protection config — they are not derived from `.github/workflows/ci.yml`'s `matrix.service` list. The protection config itself (`strict: false`, `enforce_admins: false`, `required_pull_request_reviews: null`) also exists only as live GitHub state, not in any versioned file. If a future phase adds a new service to the CI matrix, its check will run but won't be required, so the gate silently covers less than it appears to, with nothing in the repo to flag the gap.

**Why it exists:** `gh api` was the only way to configure branch protection without interactive GitHub UI access in this environment. Recording the payload inline in a dated plan document isn't enough, since plans are point-in-time snapshots per project convention, not living docs — there's no versioned file that reflects current live state.

**Planned resolution:** When a phase adds a new service to the CI matrix (e.g. Phase 3 adding `inventory` and `notifications`), the branch protection rule's required checks must be updated in the same change: `gh api --method PUT repos/<owner>/<repo>/branches/main/protection` with the new service's `test (<service>)` context added to the required list. Call this out explicitly in that phase's plan so it isn't missed.

## Resolved

_(none yet)_
