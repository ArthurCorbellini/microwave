# RabbitMQ — concepts

**Written:** 2026-08-19, while reviewing the Phase 3 PR. A snapshot of the
mental model, not a living doc — unlike `docs/conventions.md` (which states
*this project's* rules and must stay in sync with the code), this file
explains the *general* concepts behind RabbitMQ, grounded in this project's
actual code as of Phase 3. If the code changes later, the concepts here
mostly still hold; only the concrete file/line references might drift.

For "what pattern does this project use and why," see
`docs/conventions.md`'s "Messaging (RabbitMQ and Kafka)" section instead —
that's the quick-reference version, written for someone who already knows
what an exchange is. This file is for someone who doesn't yet.

## RabbitMQ vs. Kafka: not two flavors of the same tool

Before the specifics: RabbitMQ and Kafka are not interchangeable tools
wearing different branding — they're built on genuinely different core
models, and forcing one to imitate the other fights its actual design.

**RabbitMQ is a real queue (smart broker, simple consumer).** The broker
tracks every message: which queue it's in, whether it's been delivered,
whether it's been acknowledged. Once a consumer acknowledges a message, it's
**gone** — consumed, done. This is built for distributing work: many
messages, one or more workers competing for them, each message processed
once by someone.

**Kafka is a commit log (dumb broker, smart consumer).** The broker just
appends each message to the end of an ordered, immutable log, partitioned by
topic, and retains it for a configured period — it doesn't track who's read
what. The **consumer** tracks its own position (offset). This means multiple,
completely independent applications can read the same topic from the
beginning, and a new consumer can show up weeks later and replay everything
since.

This project uses RabbitMQ for **point-to-point commands** (`orders` asking
`inventory` to do something specific, expecting a specific reply — see
`OrderService.createOrder`/`handleInventoryReserved`) and Kafka for
**domain events** (`orders` announcing "an order was created," not knowing
or caring who's listening — see `OrderEventPublisher`). Trying to make
RabbitMQ do Kafka's job would mean losing replay (once consumed, a message
is gone — no replaying history for a consumer that comes back online days
later). Trying to make Kafka do RabbitMQ's job would mean losing native
routing, per-message TTL, and broker-native dead-lettering — you'd have to
build all of that yourself.

## The core model: exchange, queue, binding

A publisher in RabbitMQ never sends a message directly to a queue — it's
not allowed to. It always publishes to an **exchange**, addressed by name
and a **routing key**:

```java
// services/orders/.../inventory/ReservationCommandPublisher.java
rabbitTemplate.convertAndSend(
    RabbitMQConfig.INVENTORY_EXCHANGE,          // "inventory.exchange"
    RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY,   // "reserve-stock"
    new ReserveStockCommand(orderId, productId, quantity));
```

### Exchange — the router, stores nothing

An exchange receives published messages and decides, based on configured
rules, which queue(s) to forward them to. It never stores anything itself.

```java
// services/inventory/.../config/RabbitMQConfig.java
@Bean
DirectExchange inventoryExchange() {
  return new DirectExchange(INVENTORY_EXCHANGE);
}
```

`DirectExchange` is the simplest exchange type: it routes by exact match on
the routing key. (Other types exist — `fanout` broadcasts to everyone
bound, `topic` matches wildcard patterns like `order.*.created` — this
project only uses `direct`, since every hop here has exactly one intended
recipient.)

### Why not just address the queue directly?

Because the publisher shouldn't need to know (or care) which queue holds
the message. `orders` only knows two things: the exchange's name and a
routing key — it never references a queue name. This decouples "what
happened" (the publisher's concern) from "where it's stored internally"
(the receiving service's concern). If `inventory` later needs to split its
processing across two queues, it can rewire its own bindings without
`orders` changing a line — the routing key contract stays stable.

### Queue — where messages actually live

```java
// services/inventory/.../config/RabbitMQConfig.java
@Bean
Queue reserveStockQueue() {
  return QueueBuilder.durable(RESERVE_STOCK_QUEUE)   // "inventory.reserve-stock.queue"
      .withArgument("x-dead-letter-exchange", INVENTORY_DLX)
      .withArgument("x-dead-letter-routing-key", RESERVE_STOCK_ROUTING_KEY)
      .build();
}
```

Unlike an exchange, a queue really stores messages, roughly FIFO, until a
consumer takes them. `durable` means the queue survives a broker restart
(persisted to disk, not just memory). If no consumer is listening at the
moment, the message isn't lost — it waits.

A consumer attaches directly to a queue by name, never to an exchange:

```java
// services/inventory/.../reservation/ReserveStockListener.java
@RabbitListener(queues = RabbitMQConfig.RESERVE_STOCK_QUEUE, ...)
public void handle(ReserveStockCommand command) { ... }
```

### Binding — the rule connecting the two

```java
// services/inventory/.../config/RabbitMQConfig.java
@Bean
Binding reserveStockBinding() {
  return BindingBuilder.bind(reserveStockQueue()).to(inventoryExchange()).with(RESERVE_STOCK_ROUTING_KEY);
}
```

A binding is a registered rule: "messages arriving at this exchange with
this routing key go to this queue." Without a binding, an exchange
receives a message and has nowhere to send it — it's silently dropped, no
error. A queue can have several bindings (multiple routing keys feeding
it), and an exchange can feed several queues — this project's case is
1-to-1, but the model supports much more.

### The full path of a message

`orders` (code) → AMQP over the network → `inventory.exchange` (broker) →
broker checks its bindings, matches the routing key → copies the message
into `inventory.reserve-stock.queue` (broker) → sits there until consumed
→ `ReserveStockListener` (code, `inventory`) receives and processes it.

Note that both `orders`' and `inventory`'s `RabbitMQConfig` declare
`inventoryExchange()` — this isn't two exchanges, it's the same one
(identified by name on the broker), declared idempotently from both
codebases so publishing never races whichever service starts first (see
each config's "declared defensively" comment).

## Dead-lettering: what happens to a message that can never be processed

DLX (dead-letter exchange) and DLQ (dead-letter queue) aren't special
RabbitMQ types — they're an ordinary `DirectExchange` and an ordinary
`Queue`, reused for a different role: catching messages that fail
permanently instead of retrying forever or silently vanishing.

```java
// services/orders/.../config/RabbitMQConfig.java
DirectExchange ordersDeadLetterExchange() { return new DirectExchange(ORDERS_DLX); }   // "orders.dlx"
Queue inventoryReservedDeadLetterQueue() { return QueueBuilder.durable(INVENTORY_RESERVED_DLQ).build(); }
Binding inventoryReservedDeadLetterBinding() {
  return BindingBuilder.bind(inventoryReservedDeadLetterQueue())
      .to(ordersDeadLetterExchange())
      .with(INVENTORY_RESERVED_ROUTING_KEY);
}
```

Same exchange+queue+binding trio as before, forming a parallel "graveyard"
path. What connects the main queue to this path are two special arguments
on the main queue's declaration (seen above in the Queue section):
`x-dead-letter-exchange` / `x-dead-letter-routing-key`. These tell the
broker: "if a message here dies, don't discard it — republish it to this
exchange, with this routing key," effectively doing the same
`convertAndSend` the application would do manually, but automatically.

A message "dies" from one of three triggers: explicit rejection, TTL
expiry, or a queue-length limit exceeded. In this project it's the first
one — and it's triggered explicitly by the retry interceptor below, not by
RabbitMQ itself.

## The retry interceptor: the one piece that's Spring, not AMQP

Everything above is the RabbitMQ/AMQP model itself. This part is a
Spring AMQP layer wrapping the listener method call.

```java
// services/inventory/.../config/RabbitMQConfig.java
@Bean
StatelessRetryOperationsInterceptor retryInterceptor(RabbitTemplate rabbitTemplate) {
  return RetryInterceptorBuilder.stateless()
      .maxRetries(3)
      .backOffOptions(500, 2.0, 10_000)
      .recoverer(new RepublishMessageRecoverer(rabbitTemplate, INVENTORY_DLX, RESERVE_STOCK_ROUTING_KEY))
      .build();
}

@Bean
SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(..., StatelessRetryOperationsInterceptor retryInterceptor) {
  SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
  ...
  factory.setAdviceChain(retryInterceptor);
  return factory;
}
```

Without this, an uncaught exception in the listener would, by default,
reject-and-requeue the message immediately — a busy loop hammering the same
failing message with no pause. The interceptor wraps `handle()`:

- `maxRetries(3)` — on exception, call `handle()` again, in-process, up to
  3 times total, before giving up.
- `backOffOptions(500, 2.0, 10_000)` — exponential backoff between
  attempts: 500ms, 1000ms, 2000ms..., capped at 10s. Gives a transient
  failure (e.g. a database blip) room to recover instead of hammering it.
- `.recoverer(...)` — what to do once retries are exhausted. Note the two
  arguments: `INVENTORY_DLX` and `RESERVE_STOCK_ROUTING_KEY` — the same DLX
  and routing key from the dead-letter section above. `RepublishMessageRecoverer`
  is this interceptor explicitly publishing to the dead-letter exchange in
  code, as opposed to the queue-level `x-dead-letter-*` arguments triggering
  it automatically.

**Two paths into the same DLQ, for different reasons:** the queue-level
`x-dead-letter-*` arguments catch messages RabbitMQ itself rejects outright
(e.g. malformed JSON that never even becomes a `ReserveStockCommand` — never
reaches application code). This interceptor catches messages that *do*
reach `handle()`, run, and genuinely fail (e.g. the database is briefly
unreachable) — after 3 real attempts, not zero.

`factory.setAdviceChain(retryInterceptor)` is what wires this interceptor
into every listener using this container factory — `handle()` itself has
no idea retries exist; it just throws normally if something goes wrong.

## What's covered here vs. what isn't

This document covers the core mental model solidly: exchange, queue,
binding, routing key, dead-lettering, and Spring's retry layer on top.
That's roughly the 25-30% of RabbitMQ that's the non-negotiable foundation
— once you have it, you can read any RabbitMQ config, even one this
project doesn't use.

Not covered here, and not currently used in this project:

- **Ack/nack and prefetch (consumer concurrency/QoS)** — how a consumer
  confirms "I processed this, remove it," and how many unacked messages it
  reserves at once. Governs throughput and load-balancing across multiple
  consumers. Relevant to why `Stock`'s concurrency bug (see
  `docs/decision-log/tech-debts.md`) was masked in this phase by Spring
  AMQP's default listener concurrency of 1. Natural place to pick this up:
  Phase 4, when a second command/reply pair (`orders` ↔ `payments`) gets
  built and there's a real reason to tune it.
- **Publisher confirms** — guaranteeing the broker actually received a
  published message before the caller moves on. Directly maps to `TD-9`
  in `docs/decision-log/tech-debts.md`, already slated for Phase 7.
- **Other exchange types** (`fanout`, `topic`) — not used here because this
  project's pub/sub needs are already served by Kafka; including them would
  need an artificial use case.
- **Native AMQP RPC** (`reply-to`/`correlation-id`) — the command/reply
  pattern here is hand-built with two exchange/queue pairs; AMQP has a
  native mechanism for this that wasn't used.
- **Quorum queues / clustering / high availability** — only becomes
  meaningful with multiple broker/consumer instances, which is naturally
  Phase 5's territory (Kubernetes).
- **RabbitMQ Streams** — a newer feature that converges toward Kafka's log
  model; no natural hook in this project's roadmap.

## See also

- `docs/conventions.md` — "Messaging (RabbitMQ and Kafka)" section: the
  quick-reference version of this project's actual rules.
- `docs/decision-log/tech-debts.md` — `TD-6` through `TD-9` cover the
  concrete gaps this phase left open (compensation, DLQ monitoring, e2e
  testing, publish-failure resilience).
- `docs/architecture.md` — the macro view of which services talk to which
  over RabbitMQ vs. Kafka.
