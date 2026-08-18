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

**Planned resolution:** Phase 4 (payments moves to asynchronous messaging) replaces this synchronous chain with a RabbitMQ command/reply, which naturally supports retry and eventual consistency. Revisit this specific flow when designing Phase 4's payment-command sequence. (Phase 3 introduces the same RabbitMQ pattern first, but for Inventory — Payments stays synchronous through Phase 3, so this entry stays open until Phase 4.)

### TD-2 — Branch protection's required checks are hardcoded, not derived from the CI matrix

**Introduced in:** Phase 1.1
**Where:** `main` branch protection rule (GitHub repo settings, not a versioned file)

Branch protection on `main` requires 3 check contexts (`test (catalog)`, `test (orders)`, `test (payments)`) that are hardcoded strings in GitHub's branch protection config — they are not derived from `.github/workflows/ci.yml`'s `matrix.service` list. The protection config itself (`strict: false`, `enforce_admins: false`, `required_pull_request_reviews: null`) also exists only as live GitHub state, not in any versioned file. If a future phase adds a new service to the CI matrix, its check will run but won't be required, so the gate silently covers less than it appears to, with nothing in the repo to flag the gap.

**Why it exists:** `gh api` was the only way to configure branch protection without interactive GitHub UI access in this environment. Recording the payload inline in a dated plan document isn't enough, since plans are point-in-time snapshots per project convention, not living docs — there's no versioned file that reflects current live state.

**Planned resolution:** When a phase adds a new service to the CI matrix (e.g. Phase 3 adding `inventory` and `notifications`), the branch protection rule's required checks must be updated in the same change: `gh api --method PUT repos/<owner>/<repo>/branches/main/protection` with the new service's `test (<service>)` context added to the required list. Call this out explicitly in that phase's plan so it isn't missed.

### TD-3 — App ports published directly to the host, no gateway in front

**Introduced in:** Phase 2
**Where:** `docker-compose.yml` — `catalog`, `orders`, `payments` port mappings

All three services' ports (8081/8082/8083) are published directly to the host so the existing Postman/curl-based testing flow keeps working. There's no API Gateway or reverse proxy in front of them.

**Why it exists:** `docs/roadmap.md`'s "Deferred decisions" section already defers the API Gateway to Phase 6, where it pairs naturally with Kubernetes Ingress (Phase 5). Phase 2 continues that same deferral — it doesn't introduce a new gap, just makes the existing one visible at the container-networking level.

**Planned resolution:** two stages. Phase 6's API Gateway removes direct host port publishing, but still proxies directly to each service — a partial mitigation, not full closure, since services stay reachable, just through one more hop. Phase 8's BFF closes it fully: the Gateway is restructured to route only to the BFF, and the BFF becomes the only thing allowed to call the domain services directly. This entry only moves to `## Resolved` after Phase 8, not Phase 6.

### TD-4 — DB credentials hardcoded in `docker-compose.yml`

**Introduced in:** Phase 2
**Where:** `docker-compose.yml` — `catalog-db`, `orders-db`, `payments-db`, and the corresponding `SPRING_DATASOURCE_*` env vars on each service

Database usernames/passwords are hardcoded directly in `docker-compose.yml`, at the same security level as the plaintext credentials already present in each service's `application.yml` since Phase 1.

**Why it exists:** these aren't real secrets (local learning-project Postgres credentials), so introducing `.env`-based indirection now would add complexity without reducing any actual risk. See the Phase 2 design spec's rejected-approaches discussion for the full reasoning.

**Planned resolution:** `docs/roadmap.md`'s Phase 5 scope already includes Kubernetes `ConfigMaps/Secrets` — that's when real secret management is introduced, replacing both this and Phase 1's `application.yml` credentials.

### TD-6 — Reservations aren't released when payment is declined after a successful reservation

**Introduced in:** Phase 3
**Where:** `orders`' `OrderService.handleInventoryReserved`, `inventory`'s `Reservation`

If `inventory` successfully reserves stock but the subsequent (still synchronous, in this phase) call to `payments` is declined, the order is correctly marked `REJECTED` — but the `Reservation` stays `RESERVED` and the underlying `Stock` stays decremented. Nothing releases it.

**Why it exists:** compensation (a `ReleaseStock` command back to `inventory`) only makes sense once `payments` itself is commanded asynchronously, matching the same saga pattern — that's explicitly Phase 4's scope, not this one.

**Planned resolution:** Phase 4 (payments moves to asynchronous messaging) adds the compensating `ReleaseStock` command, triggered when a payment decline follows a successful reservation.

### TD-7 — Dead-letter queues exist, but nothing watches them

**Introduced in:** Phase 3
**Where:** `inventory`'s RabbitMQ consumer (`ReserveStock` command), `orders`' RabbitMQ consumer (`InventoryReserved` reply), `notifications`'s Kafka consumer (`OrderCreated` event), plus their respective dead-letter destinations.

After 3 retry attempts with backoff, a message that still fails processing is moved to a dead-letter queue (RabbitMQ) or a `-dlt` topic (Kafka) instead of looping forever or being silently dropped. But nothing monitors those destinations — no alerting, no automated reprocessing.

**Why it exists:** this phase is scoped to basic messaging correctness — idempotency and a dead-letter safety net are the minimum needed so a permanently-failing message doesn't take down a queue or vanish without a trace. Full resilience tooling is explicitly Phase 7's focus.

**Planned resolution:** Phase 7 (Observability and resilience) adds monitoring/alerting on dead-letter queue depth, and/or tooling to inspect and reprocess dead-lettered messages.

### TD-8 — No automated end-to-end verification across services

**Introduced in:** Phase 2
**Where:** the full request flow across `catalog`/`orders`/`payments`/`inventory`/`notifications`

There's no automated test that boots the whole system and verifies a request flows correctly end-to-end — verification is a manual checklist run by hand against the real `docker-compose` stack (see this phase's plan, Task 21). Each phase that adds services makes this checklist longer and more tedious to repeat, and easier to skip a step by accident.

**Why it exists:** each service is an independent Maven module (no parent POM, no shared test infrastructure), so there's no clean way to boot multiple services' real Spring contexts together inside a single JUnit test — a limitation of the module structure, not something skipped for time. `TD-5` (resolved in Phase 2) already flagged this as worth revisiting once Phase 3 added `inventory`/`notifications` and the manual checklist grew.

**Planned resolution:** revisit when Phase 8 (BFF) lands — a black-box system-test suite hitting the BFF's composed endpoint(s) against the real running stack (docker-compose or the Phase 5+ cluster) is the natural next step, decided properly as part of that phase's own design, not before.

### TD-9 — Order creation isn't resilient to the message brokers being unreachable

**Introduced in:** Phase 3
**Where:** `orders`' `OrderService.createOrder`

If RabbitMQ is unreachable when `orders` tries to send the `ReserveStock` command, the order is already persisted as `CREATED` but the command is never sent — the order is permanently stranded, since nothing will ever call `handleInventoryReserved` for it. If Kafka is unreachable when publishing the `OrderCreated` event, the failure is silently swallowed (the returned `CompletableFuture` isn't checked), and the event is simply lost with no signal that anything went wrong.

**Why it exists:** this phase focused on the happy-path async wiring (idempotency, retry/dead-lettering for message *processing* failures) — resilience against the message brokers themselves being unreachable during *publishing* wasn't in scope.

**Planned resolution:** revisit alongside Phase 7 (Observability and resilience), which already covers circuit breakers and is the natural place to add publish-failure handling/retry for both cases.

## Resolved

### TD-5 — No automated validation of Dockerfiles or `docker-compose.yml`

**Introduced in:** Phase 2
**Where:** `services/*/Dockerfile`, `docker-compose.yml`

CI (`.github/workflows/ci.yml`) only ran `mvn -B verify` per service; it never built the Docker images or validated `docker-compose.yml`. The 3 Dockerfiles are deliberately duplicated per service (see the Containerization section of `docs/conventions.md`), so a fix applied to one and not synced to the others would merge green and only surface when someone runs `docker-compose up` manually.

**Why it existed:** the Phase 2 design spec explicitly deferred "CI building/pushing Docker images" as out of scope, since Phase 1.1's CI gate was scoped to the Maven test suite only.

**Resolved in:** Phase 2 (same PR), by adding a `docker-build` matrix job to `.github/workflows/ci.yml` (mirroring the existing `test` matrix) that runs `docker build services/<service>` for each of the 3 services, with `docker-build (catalog)`, `docker-build (orders)`, `docker-build (payments)` added as required status checks on `main`'s branch protection alongside the existing `test (*)` checks. This closes the specific gap this entry described — a fix to one Dockerfile not synced to the others can no longer merge green.

Note: this validates that each service's **Dockerfile builds successfully**, not that `docker-compose.yml`'s own orchestration (healthchecks, `depends_on` ordering, env-var wiring) is correct — that's still verified manually only (see Task 5 of the Phase 2 plan). Revisit if that gap needs closing too, e.g. once Phase 3 adds `inventory`/`notifications` and manual verification gets more expensive to repeat by hand.
