# Kafka — concepts

**Written:** 2026-08-19, while reviewing the Phase 3 PR. A snapshot of the
mental model, not a living doc — unlike `docs/conventions.md` (which states
*this project's* rules and must stay in sync with the code), this file
explains the *general* concepts behind Kafka, grounded in this project's
actual code as of Phase 3. If the code changes later, the concepts here
mostly still hold; only the concrete file/line references might drift.

For "what pattern does this project use and why," see
`docs/conventions.md`'s "Messaging (RabbitMQ and Kafka)" section instead —
that's the quick-reference version. See `docs/concepts/rabbitmq.md` first if
you haven't — this file assumes that broker-vs-log distinction is already
understood and builds on it directly.

## No exchange, no binding — the topic does it all

RabbitMQ needed three concepts to route a message: exchange (router),
queue (storage), binding (the rule connecting them). Kafka collapses all of
that into one: the **topic**.

```java
// services/orders/.../order/OrderEventPublisher.java
kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, order.getId().toString(), event);
```

Three arguments: topic, key, value. No exchange, no routing key in the
RabbitMQ sense, no binding. This isn't a simplified special case — it's
because the flexibility an exchange+binding gives RabbitMQ (many
independent queues reacting to one publish) is already inherent to how a
Kafka topic works: any number of independent readers can consume the same
topic without any routing configuration at all. Where RabbitMQ needs a
router to fan a message out to N destinations, Kafka's log is already
readable by N independent readers with zero extra wiring.

```java
// services/orders/.../config/KafkaConfig.java
public final class KafkaConfig {
  public static final String ORDER_CREATED_TOPIC = "orders.order-created";
}
```

Just a `String` — no `@Bean` declaring the topic. Unlike a RabbitMQ queue,
which had to be explicitly built with `QueueBuilder`, a Kafka topic is
**auto-created** by the broker the first time something publishes or
subscribes to it (`auto.create.topics.enable`, on by default). This project
never declares a `NewTopic` bean, so `orders.order-created` only starts
existing on first publish — and, notably, is born with exactly 1 partition,
which matters for the next section.

## Partitions: parallelism and the only ordering guarantee that exists

A topic isn't one single log — it's split into one or more **partitions**,
each independently an ordered, immutable log. Ordering is only guaranteed
**within a partition**; across partitions, there's no relative ordering
guarantee at all.

Partitions exist for two reasons: **parallelism** (each partition can be
read by a different consumer concurrently) and **scale** (partitions can
live on different brokers in a cluster).

### The key decides the partition

```java
kafkaTemplate.send(KafkaConfig.ORDER_CREATED_TOPIC, order.getId().toString(), event);
//                                                    ^^^^^^^^^^^^^^^^^^^^^^^^ the key
```

Kafka hashes the key to pick a partition — the same key always lands on the
same partition (as long as the partition count doesn't change). A `null`
key would distribute round-robin, with no control over placement. Using
`orderId` as the key here guarantees every event about the same order stays
in relative order — relevant once more than one event type per order
exists (e.g. `OrderConfirmed` after `OrderCreated`), so a consumer could
never see them out of order.

**The catch:** since this topic is auto-created rather than declared with
an explicit `NewTopic` bean, it's born with exactly 1 partition. With 1
partition, the ordering guarantee from the key is technically true but
practically vacant — everything is already in order with only one
partition, key or no key. The intent (`orderId` as key) is correct and
future-proof; it just isn't observable yet. The final whole-branch review
flagged this as a note, not a defect — worth an explicit `NewTopic` bean
the day a second event type makes ordering matter for real.

### A structural difference from RabbitMQ worth internalizing

In RabbitMQ, once a consumer acknowledges a message, it's gone — removed
from the queue. In Kafka, a message stays in the log after being read; only
the *consumer's own position* (offset) advances. This is what lets multiple
independent consumers read the same topic from scratch without affecting
each other — nothing is ever "consumed away."

## Consumer group: how Kafka tracks "how far each reader has gotten"

```yaml
# services/notifications/src/main/resources/application.yml
spring:
  kafka:
    consumer:
      group-id: notifications-service
```

A **consumer group** is a named set of consumers that share the work of
reading a topic. Every instance of `notifications` that starts with this
same `group-id` is part of the same group, and Kafka tracks one shared read
position per group, not per instance.

**The rule:** within a group, each partition is consumed by exactly one
member at a time — this is Kafka's load-balancing mechanism. With more
partitions than the topic has today, running multiple `notifications`
instances under the same `group-id` would let Kafka assign one partition
per instance, processing in parallel. With today's single partition, a
second instance under the same group would sit idle — a hot standby that
only takes over if the first instance dies (a rebalance), not real
parallelism.

**Different groups are fully independent readers.** If another service
wanted to react to `OrderCreated` tomorrow, it would use its *own*
`group-id` — Kafka would track its progress completely separately, letting
it read the entire topic from the beginning if it wanted to, without
competing with or affecting `notifications`' own progress at all. This is
the mechanism that actually delivers on "publish once, anyone can listen"
from the RabbitMQ-vs-Kafka comparison.

```yaml
auto-offset-reset: earliest
```

Answers: "when a *brand-new* consumer group (no saved position yet) starts
reading, where does it start?" `earliest` = from the beginning of the
topic; the alternative, `latest`, would mean only messages published from
now on. `earliest` makes sense here so a service that starts up later
doesn't silently miss everything published before it existed.

```java
// services/notifications/.../notification/OrderCreatedListener.java
@KafkaListener(topics = KafkaConfig.ORDER_CREATED_TOPIC)
public void handle(OrderCreatedEvent event) { ... }
```

Note there's no `group-id` on the annotation itself — unlike
`@RabbitListener`, which pointed at an explicitly named queue per listener,
here "who am I" (the group) is an application-wide config, not something
declared per listener.

## Retry and dead-lettering: the same idea, far less code

```java
// services/notifications/.../config/KafkaConfig.java
@Bean
DefaultErrorHandler errorHandler(KafkaTemplate<Object, Object> kafkaTemplate) {
  // Spring Boot auto-wires this into the auto-configured
  // ConcurrentKafkaListenerContainerFactory — no need to redeclare that bean.
  DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);
  ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
  backOff.setMaxAttempts(3);
  return new DefaultErrorHandler(recoverer, backOff);
}
```

Same conceptual shape as RabbitMQ's retry interceptor: a layer wrapping
`handle()`, retrying on exception with exponential backoff (500ms initial,
×2 multiplier, 3 attempts) before giving up. One asymmetry worth knowing:
`ExponentialBackOff(500L, 2.0)` here has no configured max interval cap,
unlike RabbitMQ's explicit 10s ceiling (`backOffOptions(500, 2.0, 10_000)`)
— with only 3 attempts it never matters in practice, but nobody decided
this difference on purpose, it just happened.

**No manual container factory needed.** Unlike RabbitMQ, where a
`SimpleRabbitListenerContainerFactory` had to be hand-built and the
interceptor wired in via `setAdviceChain(...)`, here Spring Boot's
Kafka autoconfiguration (from `spring-boot-starter-kafka`) already builds
a `ConcurrentKafkaListenerContainerFactory` on its own, and picks up any
`DefaultErrorHandler` bean automatically. This is also why `orders`' own
`KafkaConfig` is nearly empty — with nothing to customize on the producer
side, there's nothing to declare; Spring Boot's defaults already work.

**`DeadLetterPublishingRecoverer`** takes no destination topic name — it
has a built-in naming convention: `<original-topic>` becomes
`<original-topic>-dlt`. `orders.order-created` → `orders.order-created-dlt`.
Since Kafka auto-creates topics, that `-dlt` topic only starts existing the
moment something is actually dead-lettered into it — nothing to declare in
advance. (This exact suffix, lowercase with a hyphen, was a real defect
discovered during implementation: the original design spec assumed
`.DLT`, uppercase with a dot — the actual Spring Kafka default is `-dlt`,
confirmed by inspecting the framework's bytecode. The fix is test-only;
production behavior was already correct.)

**Contrast with RabbitMQ's dead-lettering:** RabbitMQ needed an error
exchange, an error queue, a binding between them, and two arguments on the
main queue pointing at that exchange — four or five explicit pieces. Kafka
needs one bean, no topic declared anywhere. Less fine control (you don't
choose the dead-letter topic's name), but far less to wire up.

## What isn't covered by retry/DLT: publish-time failures (`TD-9`)

Everything above handles a message that *arrives* and fails to *process*.
It does not handle the broker being unreachable at the moment of
*publishing* — that gap is `TD-9` in `docs/decision-log/tech-debts.md`,
and it plays out differently on each side because `RabbitTemplate` and
`KafkaTemplate` fail in opposite ways:

- **RabbitMQ side** (`ReservationCommandPublisher.sendReserveStock`):
  `convertAndSend` is synchronous — an unreachable broker throws right
  there. Since the order was already saved a few lines earlier in
  `OrderService.createOrder` (deliberately not wrapped in a transaction —
  see the class-level comment), the client sees an error, but the order
  row is permanently stuck at `CREATED`: no command was ever sent, so
  nothing will ever call `handleInventoryReserved` for it.
- **Kafka side** (`OrderEventPublisher.publishOrderCreated`):
  `kafkaTemplate.send(...)` is asynchronous — it returns a
  `CompletableFuture` immediately and never blocks, even if Kafka is
  unreachable. The method here discards that future without a callback, so
  a publish failure is completely silent: no exception, no log, the order
  proceeds normally on every other front, and the event is simply lost
  with no signal anywhere that anything went wrong.

Same root cause (broker unreachable during publish), opposite failure
shapes, because one client blocks-and-throws and the other is
fire-and-forget by default unless you explicitly attach a callback to the
returned future.

`docs/decision-log/tech-debts.md` points `TD-9`'s resolution at Phase 7
(circuit breakers). Worth being precise about what that actually fixes: a
circuit breaker helps decide *when to stop hammering* a broker that's
already known to be down — it does not, by itself, solve what to do with
an order that's already inconsistent. The pattern that actually closes this
gap is usually a **transactional outbox**: write the order and a "pending
message" row to the same local database transaction, then a separate
poller publishes from that table with its own retry — guaranteeing the
message is never lost even if the broker was down at the exact moment of
the original save. Not decided or scheduled — just worth having the name
ready for whenever Phase 7's design actually happens.

## What's covered here vs. what isn't

This document covers topic/partition/key, consumer group, and Kafka's
retry/dead-letter layer — the core mental model needed to read this
project's Kafka usage and reason about ordering/parallelism/failure
handling.

Not covered here, and not currently used in this project:

- **Explicit topic configuration** (`NewTopic` beans: partition count,
  replication factor) — this project relies entirely on auto-creation with
  broker defaults (1 partition, replication factor tied to a single-broker
  KRaft setup). Becomes relevant the moment ordering-by-key needs to be
  real, or Kubernetes (Phase 5) introduces a multi-broker cluster.
- **Producer acks / delivery guarantees** (`acks=all`, idempotent
  producer, transactions/exactly-once semantics) — this project's producer
  uses Spring Boot's defaults; no explicit guarantee tuning was done, which
  is part of why `TD-9`'s silent-loss failure mode exists at all.
- **Batching and throughput tuning** (`linger.ms`, `batch.size`,
  compression) — irrelevant at this project's message volume, but real
  levers once throughput actually matters.
- **Consumer rebalancing protocols** — what actually happens, moment to
  moment, when a consumer joins/leaves a group and partitions get
  reassigned. Touched on conceptually above, not in mechanical detail.
- **Log compaction** — an alternative retention model (keep only the
  latest value per key, forever) suited to "current state" topics rather
  than event streams like this project's. Not used here.
- **Schema registry / Avro/Protobuf** — this project uses hand-duplicated
  JSON records per service (see `docs/conventions.md`); no schema evolution
  tooling is in place.

## See also

- `docs/concepts/rabbitmq.md` — the broker-vs-log distinction this file
  builds on, plus the general exchange/queue/binding model.
- `docs/conventions.md` — "Messaging (RabbitMQ and Kafka)" section: the
  quick-reference version of this project's actual rules.
- `docs/decision-log/tech-debts.md` — `TD-9` (publish-failure resilience,
  covered above) and `TD-7` (nothing currently watches dead-letter
  destinations).
- `docs/architecture.md` — the macro view of which services talk to which
  over RabbitMQ vs. Kafka.
