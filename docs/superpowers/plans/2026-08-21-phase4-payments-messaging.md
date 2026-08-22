# Phase 4 — Payments Messaging Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the synchronous REST/Feign call from `orders` to `payments` with a RabbitMQ command/reply (`ChargePayment`/`PaymentProcessed`), mirroring the Phase 3 `orders`↔`inventory` pattern, and add a fire-and-forget `ReleaseStock` compensation command from `orders` to `inventory` when a payment is declined after a successful reservation.

**Architecture:** Three services change. `payments` gains its own `RabbitMQConfig` (new file) and a `ChargePaymentListener` that replaces `PaymentController.charge`. `orders` replaces its `PaymentsClient` Feign call with a `PaymentCommandPublisher` + `PaymentProcessedListener` pair, and extends `ReservationCommandPublisher` with a `sendReleaseStock` method. `inventory` gains a `ReleaseStockListener` and a `ReservationService.release` method. Each service's existing `RabbitMQConfig` that owns more than one queue after this phase (`orders`, `inventory`) is split into one retry-interceptor + listener-container-factory pair *per queue*, because the existing single shared recoverer hardcodes one routing key — sharing it across two queues would dead-letter the second queue's failures into the first queue's DLQ.

**Tech Stack:** Spring Boot 4.0.7, Spring AMQP (`spring-boot-starter-amqp`), Testcontainers (RabbitMQ + PostgreSQL), JUnit 5, Mockito, AssertJ, Awaitility.

**Spec:** [`docs/superpowers/specs/2026-08-21-phase4-payments-messaging-design.md`](../specs/2026-08-21-phase4-payments-messaging-design.md)

## Global Constraints

- RabbitMQ topology: one `direct` exchange per owning service, one queue per command type, each queue with its own `<owner>.dlx`/`<owner>.<command>.dlq`, 3 retries with exponential backoff (500ms initial, ×2.0 multiplier, 10s cap) via `RetryInterceptorBuilder.stateless()`, `RepublishMessageRecoverer` on exhaustion.
- A service that sends into another service's exchange declares that exchange defensively too (idempotent declaration), exactly as `orders` already does for `inventory.exchange`.
- Message payloads are plain Java records, hand-duplicated per service — no shared library. The service that *produces* a reply gets static factory methods on its local copy (e.g. `PaymentProcessedReply.approved(...)`); the service that only *consumes* a reply gets a bare record.
- `Order` stays `CREATED`/`CONFIRMED`/`REJECTED` — no new status value. The existing `status == CREATED` guard (backed by `@Version`) protects both `InventoryReservedReply` and `PaymentProcessedReply` handling without modification.
- Every consumer that mutates state checks for a natural existing-record key before processing (idempotency), per `docs/conventions.md`.
- Kafka consumer deserialization rule (unrelated to this phase, no Kafka change here) and the RabbitMQ consumer typed-parameter rule from `docs/conventions.md` both still apply — every new `@RabbitListener` method keeps a typed payload parameter.
- All commands run from each service's own directory (`services/<service>`), since each is an independent Maven module with no parent POM.

---

## Task 1: `payments` — RabbitMQ dependency and configuration

**Files:**
- Modify: `services/payments/pom.xml`
- Modify: `services/payments/src/main/resources/application.yml`
- Create: `services/payments/src/main/java/com/microwave/payments/config/RabbitMQConfig.java`

**Interfaces:**
- Produces: `RabbitMQConfig.PAYMENTS_EXCHANGE`, `CHARGE_PAYMENT_QUEUE`, `CHARGE_PAYMENT_ROUTING_KEY`, `PAYMENTS_DLX`, `CHARGE_PAYMENT_DLQ`, `ORDERS_EXCHANGE`, `PAYMENT_PROCESSED_ROUTING_KEY` — all `public static final String` constants used by Task 3's listener. Beans `rabbitTemplate`, `rabbitListenerContainerFactory` — consumed by Task 3.

- [ ] **Step 1: Add RabbitMQ dependencies to `services/payments/pom.xml`**

Add these two `<dependency>` blocks — the `spring-boot-starter-amqp` next to the other starters, and `testcontainers-rabbitmq` next to `testcontainers-postgresql` (test scope):

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
```

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: Add RabbitMQ connection settings to `services/payments/src/main/resources/application.yml`**

Add a `rabbitmq:` block under `spring:`, matching `inventory`'s `application.yml` exactly:

```yaml
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
```

- [ ] **Step 3: Create `services/payments/src/main/java/com/microwave/payments/config/RabbitMQConfig.java`**

```java
package com.microwave.payments.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String PAYMENTS_EXCHANGE = "payments.exchange";
  public static final String CHARGE_PAYMENT_QUEUE = "payments.charge-payment.queue";
  public static final String CHARGE_PAYMENT_ROUTING_KEY = "charge-payment";
  public static final String PAYMENTS_DLX = "payments.dlx";
  public static final String CHARGE_PAYMENT_DLQ = "payments.charge-payment.dlq";

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String PAYMENT_PROCESSED_ROUTING_KEY = "payment-processed";

  @Bean
  DirectExchange paymentsExchange() {
    return new DirectExchange(PAYMENTS_EXCHANGE);
  }

  @Bean
  DirectExchange ordersExchange() {
    // Declared defensively so publishing a reply never races against orders'
    // own declaration of this exchange on startup — declaration is idempotent.
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange paymentsDeadLetterExchange() {
    return new DirectExchange(PAYMENTS_DLX);
  }

  @Bean
  Queue chargePaymentQueue() {
    return QueueBuilder.durable(CHARGE_PAYMENT_QUEUE)
        .withArgument("x-dead-letter-exchange", PAYMENTS_DLX)
        .withArgument("x-dead-letter-routing-key", CHARGE_PAYMENT_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue chargePaymentDeadLetterQueue() {
    return QueueBuilder.durable(CHARGE_PAYMENT_DLQ).build();
  }

  @Bean
  Binding chargePaymentBinding() {
    return BindingBuilder.bind(chargePaymentQueue()).to(paymentsExchange()).with(CHARGE_PAYMENT_ROUTING_KEY);
  }

  @Bean
  Binding chargePaymentDeadLetterBinding() {
    return BindingBuilder.bind(chargePaymentDeadLetterQueue()).to(paymentsDeadLetterExchange())
        .with(CHARGE_PAYMENT_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter("com.microwave.payments.payment.messaging");
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter);
    return template;
  }

  @Bean
  StatelessRetryOperationsInterceptor retryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, PAYMENTS_DLX, CHARGE_PAYMENT_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor retryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(retryInterceptor);
    return factory;
  }
}
```

- [ ] **Step 4: Verify the service still boots with the new config**

Run: `cd services/payments && mvn -B test -Dtest=PaymentsApplicationTests`
Expected: PASS (Spring context loads with the new RabbitMQ beans; no RabbitMQ broker is required for this specific test class since it's a plain context-load test, not a Testcontainers one — check `PaymentsApplicationTests.java` if this fails to confirm it doesn't need a live broker).

- [ ] **Step 5: Commit**

```bash
git add services/payments/pom.xml services/payments/src/main/resources/application.yml services/payments/src/main/java/com/microwave/payments/config/RabbitMQConfig.java
git commit -m "feat(payments): add RabbitMQ dependency and topology config"
```

---

## Task 2: `payments` — idempotent `PaymentService.charge`

**Files:**
- Modify: `services/payments/src/main/java/com/microwave/payments/payment/Payment.java`
- Modify: `services/payments/src/main/java/com/microwave/payments/payment/PaymentService.java`
- Modify: `services/payments/src/test/java/com/microwave/payments/payment/PaymentServiceTest.java`

**Interfaces:**
- Produces: `PaymentService.charge(Long orderId, BigDecimal amount)` — now idempotent (unchanged signature/return type: `Payment`), consumed by Task 3's `ChargePaymentListener`.

- [ ] **Step 1: Write the failing idempotency test**

Add to `services/payments/src/test/java/com/microwave/payments/payment/PaymentServiceTest.java` (add the `never()` static import if not already present — it already is via `org.mockito.Mockito.never` in the sibling `ReservationServiceTest`, but check this file's imports and add `import static org.mockito.Mockito.never;` if missing):

```java
  @Test
  void isIdempotentForARedeliveredOrderId() {
    initService();
    Payment existing = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
    when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

    Payment result = paymentService.charge(1L, new BigDecimal("100.00"));

    assertThat(result).isSameAs(existing);
    verify(paymentRepository, never()).save(any(Payment.class));
  }
```

Also update the two existing tests `approvesAndSavesPaymentWithinLimit` and `rejectsAndSavesPaymentAboveLimit` to stub the new lookup call (they will otherwise fail once `charge` starts calling `findByOrderId` first — Mockito's strict stubbing will pass since the stub just isn't matched by anything unexpected, but the real behavior needs it to return empty so the save path is reached). Add this line as the first line inside each of those two test methods, right after `initService();`:

```java
    when(paymentRepository.findByOrderId(anyLong())).thenReturn(Optional.empty());
```

(Add `import static org.mockito.ArgumentMatchers.anyLong;` if not already present in the file — it isn't; the file currently only imports `any`.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd services/payments && mvn -B test -Dtest=PaymentServiceTest`
Expected: FAIL — `isIdempotentForARedeliveredOrderId` fails because `charge` doesn't call `findByOrderId` yet, so `result` won't be `existing` and `save` will be called.

- [ ] **Step 3: Add the unique constraint to `Payment.java`**

In `services/payments/src/main/java/com/microwave/payments/payment/Payment.java`, change the `@Table` annotation and add the `UniqueConstraint` import:

```java
import jakarta.persistence.UniqueConstraint;
```

```java
@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(columnNames = "orderId"))
public class Payment {
```

- [ ] **Step 4: Implement the idempotency check in `PaymentService.java`**

Replace `PaymentService.charge`:

```java
package com.microwave.payments.payment;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class PaymentService {

  private final PaymentRepository paymentRepository;

  public PaymentService(PaymentRepository paymentRepository) {
    this.paymentRepository = paymentRepository;
  }

  // Idempotent: a redelivered command for an orderId that's already been
  // charged returns the existing Payment instead of processing it again —
  // same pattern as inventory's ReservationService.reserve.
  public Payment charge(Long orderId, BigDecimal amount) {
    Optional<Payment> existing = paymentRepository.findByOrderId(orderId);
    if (existing.isPresent()) {
      return existing.get();
    }

    PaymentStatus status = PaymentSimulator.decide(amount);
    return paymentRepository.save(new Payment(orderId, amount, status));
  }

  public Payment findByOrderId(Long orderId) {
    return paymentRepository.findByOrderId(orderId)
        .orElseThrow(() -> new PaymentNotFoundException(orderId));
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/payments && mvn -B test -Dtest=PaymentServiceTest`
Expected: PASS (all methods, including the new one)

- [ ] **Step 6: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/payment/Payment.java services/payments/src/main/java/com/microwave/payments/payment/PaymentService.java services/payments/src/test/java/com/microwave/payments/payment/PaymentServiceTest.java
git commit -m "feat(payments): make PaymentService.charge idempotent per orderId"
```

---

## Task 3: `payments` — `ChargePaymentListener`

**Files:**
- Create: `services/payments/src/main/java/com/microwave/payments/payment/messaging/ChargePaymentCommand.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/messaging/PaymentProcessedReply.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/ChargePaymentListener.java`
- Test: `services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerIT.java`

**Interfaces:**
- Consumes: `RabbitMQConfig.{PAYMENTS_EXCHANGE, CHARGE_PAYMENT_QUEUE, CHARGE_PAYMENT_ROUTING_KEY, ORDERS_EXCHANGE, PAYMENT_PROCESSED_ROUTING_KEY}` (Task 1), `PaymentService.charge(Long, BigDecimal)` (Task 2).
- Produces: publishes `PaymentProcessedReply` to `orders.exchange`/`payment-processed` — consumed by orders' Task 15 (`PaymentProcessedListener`).

- [ ] **Step 1: Create the message DTOs**

`services/payments/src/main/java/com/microwave/payments/payment/messaging/ChargePaymentCommand.java`:

```java
package com.microwave.payments.payment.messaging;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
```

`services/payments/src/main/java/com/microwave/payments/payment/messaging/PaymentProcessedReply.java` (producer side — carries the static factories, mirroring inventory's local `InventoryReservedReply`):

```java
package com.microwave.payments.payment.messaging;

public record PaymentProcessedReply(Long orderId, boolean approved, String reason) {

  public static PaymentProcessedReply approved(Long orderId) {
    return new PaymentProcessedReply(orderId, true, null);
  }

  public static PaymentProcessedReply declined(Long orderId, String reason) {
    return new PaymentProcessedReply(orderId, false, reason);
  }
}
```

- [ ] **Step 2: Write the failing integration test**

Create `services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerIT.java`:

```java
package com.microwave.payments.payment;

import com.microwave.payments.config.RabbitMQConfig;
import com.microwave.payments.payment.messaging.ChargePaymentCommand;
import com.microwave.payments.payment.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ChargePaymentListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_REPLY_QUEUE = "test.orders.payment-reply.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private PaymentRepository paymentRepository;

  @BeforeEach
  void setupTestQueue() {
    Queue testQueue = new Queue(TEST_REPLY_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(testQueue);

    Binding testBinding = BindingBuilder.bind(testQueue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY);
    rabbitAdmin.declareBinding(testBinding);
  }

  @Test
  void chargesAndRepliesApprovedWithinLimit() {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(42L, new BigDecimal("100.00")));

    PaymentProcessedReply reply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);
    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(42L);
    assertThat(reply.approved()).isTrue();

    Payment payment = paymentRepository.findByOrderId(42L).orElseThrow();
    assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
  }

  @Test
  void chargesAndRepliesDeclinedAboveLimit() {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(43L, new BigDecimal("15000.00")));

    PaymentProcessedReply reply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);
    assertThat(reply).isNotNull();
    assertThat(reply.orderId()).isEqualTo(43L);
    assertThat(reply.approved()).isFalse();
    assertThat(reply.reason()).isEqualTo("AMOUNT_EXCEEDS_LIMIT");
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/payments && mvn -B verify -Dit.test=ChargePaymentListenerIT`
Expected: FAIL to compile — `ChargePaymentListener` doesn't exist yet.

- [ ] **Step 4: Implement `ChargePaymentListener`**

`services/payments/src/main/java/com/microwave/payments/payment/ChargePaymentListener.java`:

```java
package com.microwave.payments.payment;

import com.microwave.payments.config.RabbitMQConfig;
import com.microwave.payments.payment.messaging.ChargePaymentCommand;
import com.microwave.payments.payment.messaging.PaymentProcessedReply;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChargePaymentListener {

  private final PaymentService paymentService;
  private final RabbitTemplate rabbitTemplate;

  public ChargePaymentListener(PaymentService paymentService, RabbitTemplate rabbitTemplate) {
    this.paymentService = paymentService;
    this.rabbitTemplate = rabbitTemplate;
  }

  @RabbitListener(queues = RabbitMQConfig.CHARGE_PAYMENT_QUEUE, containerFactory = "rabbitListenerContainerFactory")
  public void handle(ChargePaymentCommand command) {
    Payment payment = paymentService.charge(command.orderId(), command.amount());
    PaymentProcessedReply reply = payment.getStatus() == PaymentStatus.APPROVED
        ? PaymentProcessedReply.approved(command.orderId())
        : PaymentProcessedReply.declined(command.orderId(), "AMOUNT_EXCEEDS_LIMIT");

    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/payments && mvn -B verify -Dit.test=ChargePaymentListenerIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/payment/messaging services/payments/src/main/java/com/microwave/payments/payment/ChargePaymentListener.java services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerIT.java
git commit -m "feat(payments): add ChargePayment RabbitMQ listener"
```

---

## Task 4: `payments` — resilience tests for `ChargePaymentListener`

**Files:**
- Test: `services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerResilienceIT.java`

**Interfaces:**
- Consumes: everything from Tasks 1-3 (no production code changes in this task).

- [ ] **Step 1: Write the duplicate-delivery and dead-letter tests**

Create `services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerResilienceIT.java`:

```java
package com.microwave.payments.payment;

import com.microwave.payments.config.RabbitMQConfig;
import com.microwave.payments.payment.messaging.ChargePaymentCommand;
import com.microwave.payments.payment.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ChargePaymentListenerResilienceIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_REPLY_QUEUE = "test.orders.payment-reply.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private PaymentRepository paymentRepository;

  @BeforeEach
  void bindTestReplyQueue() {
    // autoDelete=false: the idempotency test performs two receiveAndConvert
    // calls in a row, each of which opens and cancels a consumer. With
    // autoDelete=true, the queue is removed the instant the first consumer
    // disconnects, so the second receive would fail with NOT_FOUND. The
    // queue is still scoped to this test class's Testcontainers broker, so
    // there's no real leak risk.
    Queue queue = new Queue(TEST_REPLY_QUEUE, true, false, false);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.ORDERS_EXCHANGE))
        .with(RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void isIdempotentForADuplicateCommand() {
    ChargePaymentCommand command = new ChargePaymentCommand(44L, new BigDecimal("100.00"));

    rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY, command);
    rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    rabbitTemplate.convertAndSend(RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY, command);
    PaymentProcessedReply secondReply =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(TEST_REPLY_QUEUE, 10000);

    assertThat(secondReply).isNotNull();
    assertThat(secondReply.approved()).isTrue();

    // Only one Payment row exists for this orderId — the second delivery hit
    // the idempotency check in PaymentService.charge() and never saved again.
    List<Payment> payments = paymentRepository.findAll().stream()
        .filter(p -> p.getOrderId().equals(44L))
        .toList();
    assertThat(payments).hasSize(1);
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // A missing order id breaks Payment's not-null column constraint when
    // saving. PaymentService.charge only special-cases an already-existing
    // Payment, so this kind of failure is guaranteed to exhaust all 3
    // retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(null, new BigDecimal("100.00")));

    ChargePaymentCommand deadLettered =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(RabbitMQConfig.CHARGE_PAYMENT_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.amount()).isEqualByComparingTo("100.00");
  }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `cd services/payments && mvn -B verify -Dit.test=ChargePaymentListenerResilienceIT`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add services/payments/src/test/java/com/microwave/payments/payment/ChargePaymentListenerResilienceIT.java
git commit -m "test(payments): cover ChargePayment idempotency and dead-lettering"
```

---

## Task 5: `payments` — remove `POST /payments`

**Files:**
- Modify: `services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java`
- Delete: `services/payments/src/main/java/com/microwave/payments/payment/rest/PaymentRequest.java`
- Modify: `services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java`

**Interfaces:**
- No production interface changes — `GET /payments/{orderId}` (`PaymentController.getByOrderId`) and `PaymentResponse` are unchanged.

- [ ] **Step 1: Remove the failing-first assertion — delete the two POST tests**

In `services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java`, delete the `chargesPayment` and `rejectsPaymentWithNullOrderId` test methods, and remove the now-unused `post` static import (`org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post`). The file should end up as:

```java
package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentService paymentService;

  @Test
  void getsPaymentByOrderId() throws Exception {
    Payment payment = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
    when(paymentService.findByOrderId(1L)).thenReturn(payment);

    mockMvc.perform(get("/payments/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void returnsNotFoundForMissingPayment() throws Exception {
    when(paymentService.findByOrderId(99L)).thenThrow(new PaymentNotFoundException(99L));

    mockMvc.perform(get("/payments/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Payment not found for order: 99"))
        .andExpect(jsonPath("$.instance").value("/payments/99"));
  }
}
```

- [ ] **Step 2: Remove `PaymentController.charge` and delete `PaymentRequest`**

In `services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java`, delete the `charge` method (the whole `@Operation`/`@ApiResponse`/`@PostMapping`/`@ResponseStatus` block) and the now-unused imports (`PaymentRequest`, `jakarta.validation.Valid`, `HttpStatus`, `PostMapping`, `RequestBody`, `ResponseStatus`). The file should end up as:

```java
package com.microwave.payments.payment;

import com.microwave.payments.payment.rest.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @Operation(summary = "Get a payment by order ID")
  @ApiResponse(responseCode = "200", description = "Payment found")
  @ApiResponse(responseCode = "404", description = "No payment exists for that order",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping("/{orderId}")
  public PaymentResponse getByOrderId(@PathVariable Long orderId) {
    return PaymentResponse.from(paymentService.findByOrderId(orderId));
  }
}
```

Delete `services/payments/src/main/java/com/microwave/payments/payment/rest/PaymentRequest.java`:

```bash
git rm services/payments/src/main/java/com/microwave/payments/payment/rest/PaymentRequest.java
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `cd services/payments && mvn -B test -Dtest=PaymentControllerTest`
Expected: PASS (2 tests, both GET-based)

- [ ] **Step 4: Run the full payments test suite to confirm nothing else broke**

Run: `cd services/payments && mvn -B verify`
Expected: PASS (all unit + integration tests)

- [ ] **Step 5: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java
git commit -m "refactor(payments): remove POST /payments now that charging is command-driven"
```

---

## Task 6: `payments` — `docker-compose.yml` RabbitMQ wiring

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:** none (infrastructure only).

- [ ] **Step 1: Add RabbitMQ env vars and dependency to the `payments` service block**

In `docker-compose.yml`, find the `payments:` service block (currently only has `SPRING_DATASOURCE_*` env vars and depends on `payments-db`). Change it to match `inventory`'s shape:

```yaml
  payments:
    build: ./services/payments
    restart: unless-stopped
    ports:
      - "127.0.0.1:8082:8082"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://payments-db:5432/payments_db
      SPRING_DATASOURCE_USERNAME: payments
      SPRING_DATASOURCE_PASSWORD: payments
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
    depends_on:
      payments-db:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 30s
```

- [ ] **Step 2: Validate the compose file**

Run: `docker-compose config --quiet`
Expected: no output, exit code 0 (valid YAML, all interpolations resolve)

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "chore: wire payments to RabbitMQ in docker-compose"
```

---

## Task 7: `inventory` — split `RabbitMQConfig` into per-queue retry/factory beans

**Files:**
- Modify: `services/inventory/src/main/java/com/microwave/inventory/config/RabbitMQConfig.java`
- Modify: `services/inventory/src/main/java/com/microwave/inventory/reservation/ReserveStockListener.java`

**Interfaces:**
- Produces: `RabbitMQConfig.{RELEASE_STOCK_QUEUE, RELEASE_STOCK_ROUTING_KEY, RELEASE_STOCK_DLQ}` — consumed by Task 9's `ReleaseStockListener`. Bean `releaseStockListenerContainerFactory` — consumed by Task 9.

- [ ] **Step 1: Replace `services/inventory/src/main/java/com/microwave/inventory/config/RabbitMQConfig.java`**

```java
package com.microwave.inventory.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String INVENTORY_EXCHANGE = "inventory.exchange";
  public static final String RESERVE_STOCK_QUEUE = "inventory.reserve-stock.queue";
  public static final String RESERVE_STOCK_ROUTING_KEY = "reserve-stock";
  public static final String RELEASE_STOCK_QUEUE = "inventory.release-stock.queue";
  public static final String RELEASE_STOCK_ROUTING_KEY = "release-stock";
  public static final String INVENTORY_DLX = "inventory.dlx";
  public static final String RESERVE_STOCK_DLQ = "inventory.reserve-stock.dlq";
  public static final String RELEASE_STOCK_DLQ = "inventory.release-stock.dlq";

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String INVENTORY_RESERVED_ROUTING_KEY = "inventory-reserved";

  @Bean
  DirectExchange inventoryExchange() {
    return new DirectExchange(INVENTORY_EXCHANGE);
  }

  @Bean
  DirectExchange ordersExchange() {
    // Declared defensively so publishing a reply never races against orders'
    // own declaration of this exchange on startup — declaration is idempotent.
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange inventoryDeadLetterExchange() {
    return new DirectExchange(INVENTORY_DLX);
  }

  @Bean
  Queue reserveStockQueue() {
    return QueueBuilder.durable(RESERVE_STOCK_QUEUE)
        .withArgument("x-dead-letter-exchange", INVENTORY_DLX)
        .withArgument("x-dead-letter-routing-key", RESERVE_STOCK_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue reserveStockDeadLetterQueue() {
    return QueueBuilder.durable(RESERVE_STOCK_DLQ).build();
  }

  @Bean
  Binding reserveStockBinding() {
    return BindingBuilder.bind(reserveStockQueue()).to(inventoryExchange()).with(RESERVE_STOCK_ROUTING_KEY);
  }

  @Bean
  Binding reserveStockDeadLetterBinding() {
    return BindingBuilder.bind(reserveStockDeadLetterQueue()).to(inventoryDeadLetterExchange())
        .with(RESERVE_STOCK_ROUTING_KEY);
  }

  @Bean
  Queue releaseStockQueue() {
    return QueueBuilder.durable(RELEASE_STOCK_QUEUE)
        .withArgument("x-dead-letter-exchange", INVENTORY_DLX)
        .withArgument("x-dead-letter-routing-key", RELEASE_STOCK_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue releaseStockDeadLetterQueue() {
    return QueueBuilder.durable(RELEASE_STOCK_DLQ).build();
  }

  @Bean
  Binding releaseStockBinding() {
    return BindingBuilder.bind(releaseStockQueue()).to(inventoryExchange()).with(RELEASE_STOCK_ROUTING_KEY);
  }

  @Bean
  Binding releaseStockDeadLetterBinding() {
    return BindingBuilder.bind(releaseStockDeadLetterQueue()).to(inventoryDeadLetterExchange())
        .with(RELEASE_STOCK_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter("com.microwave.inventory.reservation.messaging");
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter);
    return template;
  }

  @Bean
  StatelessRetryOperationsInterceptor reserveStockRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, INVENTORY_DLX, RESERVE_STOCK_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory reserveStockListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor reserveStockRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(reserveStockRetryInterceptor);
    return factory;
  }

  @Bean
  StatelessRetryOperationsInterceptor releaseStockRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, INVENTORY_DLX, RELEASE_STOCK_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory releaseStockListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor releaseStockRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(releaseStockRetryInterceptor);
    return factory;
  }
}
```

- [ ] **Step 2: Update `ReserveStockListener`'s container factory reference**

In `services/inventory/src/main/java/com/microwave/inventory/reservation/ReserveStockListener.java`, change:

```java
  @RabbitListener(queues = RabbitMQConfig.RESERVE_STOCK_QUEUE, containerFactory = "rabbitListenerContainerFactory")
```

to:

```java
  @RabbitListener(queues = RabbitMQConfig.RESERVE_STOCK_QUEUE, containerFactory = "reserveStockListenerContainerFactory")
```

- [ ] **Step 3: Run the existing inventory RabbitMQ tests to confirm the split didn't break anything**

Run: `cd services/inventory && mvn -B verify -Dit.test=ReserveStockListenerIT,ReserveStockListenerResilienceIT`
Expected: PASS (both classes, same behavior as before — this step is a regression check for the rename)

- [ ] **Step 4: Commit**

```bash
git add services/inventory/src/main/java/com/microwave/inventory/config/RabbitMQConfig.java services/inventory/src/main/java/com/microwave/inventory/reservation/ReserveStockListener.java
git commit -m "refactor(inventory): split RabbitMQConfig into per-queue retry interceptors

The shared retryInterceptor hardcoded RESERVE_STOCK_ROUTING_KEY as the
dead-letter target — safe with one owned queue, but a second queue
sharing the same interceptor would dead-letter into the wrong DLQ.
Splitting now, ahead of Task 9 adding release-stock."
```

---

## Task 8: `inventory` — `ReservationService.release`

**Files:**
- Modify: `services/inventory/src/main/java/com/microwave/inventory/stock/Stock.java`
- Modify: `services/inventory/src/main/java/com/microwave/inventory/reservation/Reservation.java`
- Modify: `services/inventory/src/main/java/com/microwave/inventory/reservation/ReservationService.java`
- Modify: `services/inventory/src/test/java/com/microwave/inventory/reservation/ReservationServiceTest.java`

**Interfaces:**
- Produces: `ReservationService.release(Long orderId)` (returns `void`) — consumed by Task 9's `ReleaseStockListener`. `Stock.increase(int quantity)`, `Reservation.markReleased()`.

- [ ] **Step 1: Write the failing tests**

Add to `services/inventory/src/test/java/com/microwave/inventory/reservation/ReservationServiceTest.java`:

```java
  @Test
  void releasesAReservedReservationAndRestoresStock() {
    initService();
    Reservation reservation = new Reservation(42L, 1L, 5, ReservationStatus.RESERVED);
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.of(reservation));
    when(stockRepository.findByProductId(1L)).thenReturn(Optional.of(new Stock(1L, 45)));

    reservationService.release(42L);

    ArgumentCaptor<Stock> stockCaptor = ArgumentCaptor.forClass(Stock.class);
    verify(stockRepository).save(stockCaptor.capture());
    assertThat(stockCaptor.getValue().getAvailableQuantity()).isEqualTo(50);

    ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
    verify(reservationRepository).save(reservationCaptor.capture());
    assertThat(reservationCaptor.getValue().getStatus()).isEqualTo(ReservationStatus.RELEASED);
  }

  @Test
  void isIdempotentForAnAlreadyReleasedReservation() {
    initService();
    Reservation reservation = new Reservation(42L, 1L, 5, ReservationStatus.RELEASED);
    when(reservationRepository.findByOrderId(42L)).thenReturn(Optional.of(reservation));

    reservationService.release(42L);

    verify(stockRepository, never()).findByProductId(any());
    verify(stockRepository, never()).save(any(Stock.class));
    verify(reservationRepository, never()).save(any(Reservation.class));
  }

  @Test
  void throwsReservationNotFoundWhenReleasingUnknownOrder() {
    initService();
    when(reservationRepository.findByOrderId(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> reservationService.release(99L))
        .isInstanceOf(ReservationNotFoundException.class);
  }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd services/inventory && mvn -B test -Dtest=ReservationServiceTest`
Expected: FAIL to compile — `ReservationService.release` doesn't exist yet.

- [ ] **Step 3: Add `Stock.increase`**

In `services/inventory/src/main/java/com/microwave/inventory/stock/Stock.java`, add next to `decrease`:

```java
  public void increase(int quantity) {
    this.availableQuantity += quantity;
  }
```

- [ ] **Step 4: Add `Reservation.markReleased`**

In `services/inventory/src/main/java/com/microwave/inventory/reservation/Reservation.java`, add next to the constructor:

```java
  public void markReleased() {
    this.status = ReservationStatus.RELEASED;
  }
```

- [ ] **Step 5: Implement `ReservationService.release`**

In `services/inventory/src/main/java/com/microwave/inventory/reservation/ReservationService.java`, add the import `import com.microwave.inventory.stock.Stock;` if not already present (it already is), and add this method after `reserve`:

```java
  // Idempotent: releasing an already-RELEASED reservation is a no-op, so a
  // redelivered ReleaseStock command doesn't restore Stock twice.
  // @Transactional so a failure saving the Reservation rolls back the Stock
  // increase too.
  @Transactional
  public void release(Long orderId) {
    Reservation reservation = reservationRepository.findByOrderId(orderId)
        .orElseThrow(() -> new ReservationNotFoundException(orderId));

    if (reservation.getStatus() == ReservationStatus.RELEASED) {
      return;
    }

    Stock stock = stockRepository.findByProductId(reservation.getProductId()).orElseThrow();
    stock.increase(reservation.getQuantity());
    stockRepository.save(stock);

    reservation.markReleased();
    reservationRepository.save(reservation);
  }
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd services/inventory && mvn -B test -Dtest=ReservationServiceTest`
Expected: PASS (all methods, including the 3 new ones)

- [ ] **Step 7: Commit**

```bash
git add services/inventory/src/main/java/com/microwave/inventory/stock/Stock.java services/inventory/src/main/java/com/microwave/inventory/reservation/Reservation.java services/inventory/src/main/java/com/microwave/inventory/reservation/ReservationService.java services/inventory/src/test/java/com/microwave/inventory/reservation/ReservationServiceTest.java
git commit -m "feat(inventory): add ReservationService.release for stock compensation"
```

---

## Task 9: `inventory` — `ReleaseStockListener`

**Files:**
- Create: `services/inventory/src/main/java/com/microwave/inventory/reservation/messaging/ReleaseStockCommand.java`
- Create: `services/inventory/src/main/java/com/microwave/inventory/reservation/ReleaseStockListener.java`
- Test: `services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerIT.java`

**Interfaces:**
- Consumes: `RabbitMQConfig.{RELEASE_STOCK_QUEUE, releaseStockListenerContainerFactory}` (Task 7), `ReservationService.release(Long)` (Task 8).

- [ ] **Step 1: Create the message DTO**

`services/inventory/src/main/java/com/microwave/inventory/reservation/messaging/ReleaseStockCommand.java`:

```java
package com.microwave.inventory.reservation.messaging;

public record ReleaseStockCommand(Long orderId) {
}
```

- [ ] **Step 2: Write the failing integration test**

Create `services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerIT.java`:

```java
package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.ReleaseStockCommand;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class ReleaseStockListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private ReservationRepository reservationRepository;

  @Autowired
  private StockRepository stockRepository;

  @Test
  void releasesReservationAndRestoresStock() {
    stockRepository.save(new Stock(10L, 45));
    reservationRepository.save(new Reservation(50L, 10L, 5, ReservationStatus.RESERVED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(50L));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Reservation reservation = reservationRepository.findByOrderId(50L).orElseThrow();
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    });

    Optional<Stock> stock = stockRepository.findByProductId(10L);
    assertThat(stock).isPresent();
    assertThat(stock.get().getAvailableQuantity()).isEqualTo(50);
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/inventory && mvn -B verify -Dit.test=ReleaseStockListenerIT`
Expected: FAIL to compile — `ReleaseStockListener` doesn't exist yet.

- [ ] **Step 4: Implement `ReleaseStockListener`**

`services/inventory/src/main/java/com/microwave/inventory/reservation/ReleaseStockListener.java`:

```java
package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.ReleaseStockCommand;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ReleaseStockListener {

  private final ReservationService reservationService;

  public ReleaseStockListener(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @RabbitListener(queues = RabbitMQConfig.RELEASE_STOCK_QUEUE, containerFactory = "releaseStockListenerContainerFactory")
  public void handle(ReleaseStockCommand command) {
    reservationService.release(command.orderId());
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/inventory && mvn -B verify -Dit.test=ReleaseStockListenerIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/inventory/src/main/java/com/microwave/inventory/reservation/messaging/ReleaseStockCommand.java services/inventory/src/main/java/com/microwave/inventory/reservation/ReleaseStockListener.java services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerIT.java
git commit -m "feat(inventory): add ReleaseStock RabbitMQ listener"
```

---

## Task 10: `inventory` — resilience tests for `ReleaseStockListener`

**Files:**
- Test: `services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerResilienceIT.java`

**Interfaces:**
- Consumes: everything from Tasks 7-9 (no production code changes in this task).

- [ ] **Step 1: Write the duplicate-delivery and dead-letter tests**

Create `services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerResilienceIT.java`:

```java
package com.microwave.inventory.reservation;

import com.microwave.inventory.config.RabbitMQConfig;
import com.microwave.inventory.reservation.messaging.ReleaseStockCommand;
import com.microwave.inventory.stock.Stock;
import com.microwave.inventory.stock.StockRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class ReleaseStockListenerResilienceIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private ReservationRepository reservationRepository;

  @Autowired
  private StockRepository stockRepository;

  @Test
  void isIdempotentForADuplicateCommand() {
    stockRepository.save(new Stock(11L, 45));
    reservationRepository.save(new Reservation(51L, 11L, 5, ReservationStatus.RESERVED));
    ReleaseStockCommand command = new ReleaseStockCommand(51L);

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY, command);
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Reservation reservation = reservationRepository.findByOrderId(51L).orElseThrow();
      assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    });

    rabbitTemplate.convertAndSend(RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY, command);

    // Give the second delivery time to process, then assert Stock was only
    // restored once (45 + 5 = 50, not 45 + 5 + 5 = 55).
    await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Optional<Stock> stock = stockRepository.findByProductId(11L);
      assertThat(stock).isPresent();
      assertThat(stock.get().getAvailableQuantity()).isEqualTo(50);
    });
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // No Reservation exists for this orderId, so ReservationService.release
    // always throws ReservationNotFoundException — guaranteed to exhaust all
    // 3 retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(999L));

    ReleaseStockCommand deadLettered =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(RabbitMQConfig.RELEASE_STOCK_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.orderId()).isEqualTo(999L);
  }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `cd services/inventory && mvn -B verify -Dit.test=ReleaseStockListenerResilienceIT`
Expected: PASS

- [ ] **Step 3: Run the full inventory test suite to confirm nothing else broke**

Run: `cd services/inventory && mvn -B verify`
Expected: PASS (all unit + integration tests)

- [ ] **Step 4: Commit**

```bash
git add services/inventory/src/test/java/com/microwave/inventory/reservation/ReleaseStockListenerResilienceIT.java
git commit -m "test(inventory): cover ReleaseStock idempotency and dead-lettering"
```

---

## Task 11: `orders` — split `RabbitMQConfig` and declare `payments.exchange`

**Files:**
- Modify: `services/orders/src/main/java/com/microwave/orders/config/RabbitMQConfig.java`
- Modify: `services/orders/src/main/java/com/microwave/orders/inventory/InventoryReservedListener.java`

**Interfaces:**
- Produces: `RabbitMQConfig.{PAYMENTS_EXCHANGE, CHARGE_PAYMENT_ROUTING_KEY, RELEASE_STOCK_ROUTING_KEY, PAYMENT_PROCESSED_QUEUE, PAYMENT_PROCESSED_ROUTING_KEY, PAYMENT_PROCESSED_DLQ}` — consumed by Tasks 12, 13, 15. Bean `paymentReplyListenerContainerFactory` — consumed by Task 15.

- [ ] **Step 1: Replace `services/orders/src/main/java/com/microwave/orders/config/RabbitMQConfig.java`**

```java
package com.microwave.orders.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

  public static final String ORDERS_EXCHANGE = "orders.exchange";
  public static final String INVENTORY_RESERVED_QUEUE = "orders.inventory-reply.queue";
  public static final String INVENTORY_RESERVED_ROUTING_KEY = "inventory-reserved";
  public static final String ORDERS_DLX = "orders.dlx";
  public static final String INVENTORY_RESERVED_DLQ = "orders.inventory-reply.dlq";

  public static final String INVENTORY_EXCHANGE = "inventory.exchange";
  public static final String RESERVE_STOCK_ROUTING_KEY = "reserve-stock";
  public static final String RELEASE_STOCK_ROUTING_KEY = "release-stock";

  public static final String PAYMENTS_EXCHANGE = "payments.exchange";
  public static final String CHARGE_PAYMENT_ROUTING_KEY = "charge-payment";
  public static final String PAYMENT_PROCESSED_QUEUE = "orders.payment-reply.queue";
  public static final String PAYMENT_PROCESSED_ROUTING_KEY = "payment-processed";
  public static final String PAYMENT_PROCESSED_DLQ = "orders.payment-reply.dlq";

  @Bean
  DirectExchange ordersExchange() {
    return new DirectExchange(ORDERS_EXCHANGE);
  }

  @Bean
  DirectExchange inventoryExchange() {
    // Declared defensively so publishing ReserveStock/ReleaseStock never
    // races against inventory's own declaration of this exchange on startup.
    return new DirectExchange(INVENTORY_EXCHANGE);
  }

  @Bean
  DirectExchange paymentsExchange() {
    // Declared defensively so publishing ChargePayment never races against
    // payments' own declaration of this exchange on startup.
    return new DirectExchange(PAYMENTS_EXCHANGE);
  }

  @Bean
  DirectExchange ordersDeadLetterExchange() {
    return new DirectExchange(ORDERS_DLX);
  }

  @Bean
  Queue inventoryReservedQueue() {
    return QueueBuilder.durable(INVENTORY_RESERVED_QUEUE)
        .withArgument("x-dead-letter-exchange", ORDERS_DLX)
        .withArgument("x-dead-letter-routing-key", INVENTORY_RESERVED_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue inventoryReservedDeadLetterQueue() {
    return QueueBuilder.durable(INVENTORY_RESERVED_DLQ).build();
  }

  @Bean
  Binding inventoryReservedBinding() {
    return BindingBuilder.bind(inventoryReservedQueue()).to(ordersExchange()).with(INVENTORY_RESERVED_ROUTING_KEY);
  }

  @Bean
  Binding inventoryReservedDeadLetterBinding() {
    return BindingBuilder.bind(inventoryReservedDeadLetterQueue()).to(ordersDeadLetterExchange())
        .with(INVENTORY_RESERVED_ROUTING_KEY);
  }

  @Bean
  Queue paymentProcessedQueue() {
    return QueueBuilder.durable(PAYMENT_PROCESSED_QUEUE)
        .withArgument("x-dead-letter-exchange", ORDERS_DLX)
        .withArgument("x-dead-letter-routing-key", PAYMENT_PROCESSED_ROUTING_KEY)
        .build();
  }

  @Bean
  Queue paymentProcessedDeadLetterQueue() {
    return QueueBuilder.durable(PAYMENT_PROCESSED_DLQ).build();
  }

  @Bean
  Binding paymentProcessedBinding() {
    return BindingBuilder.bind(paymentProcessedQueue()).to(ordersExchange()).with(PAYMENT_PROCESSED_ROUTING_KEY);
  }

  @Bean
  Binding paymentProcessedDeadLetterBinding() {
    return BindingBuilder.bind(paymentProcessedDeadLetterQueue()).to(ordersDeadLetterExchange())
        .with(PAYMENT_PROCESSED_ROUTING_KEY);
  }

  @Bean
  MessageConverter jsonMessageConverter() {
    return new JacksonJsonMessageConverter(
        "com.microwave.orders.inventory.messaging", "com.microwave.orders.payments.messaging");
  }

  @Bean
  RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMessageConverter(jsonMessageConverter);
    return template;
  }

  @Bean
  StatelessRetryOperationsInterceptor inventoryReplyRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, ORDERS_DLX, INVENTORY_RESERVED_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory inventoryReplyListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor inventoryReplyRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(inventoryReplyRetryInterceptor);
    return factory;
  }

  @Bean
  StatelessRetryOperationsInterceptor paymentReplyRetryInterceptor(RabbitTemplate rabbitTemplate) {
    return RetryInterceptorBuilder.stateless()
        .maxRetries(3)
        .backOffOptions(500, 2.0, 10_000)
        .recoverer(new RepublishMessageRecoverer(rabbitTemplate, ORDERS_DLX, PAYMENT_PROCESSED_ROUTING_KEY))
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory paymentReplyListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter,
      StatelessRetryOperationsInterceptor paymentReplyRetryInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(jsonMessageConverter);
    factory.setAdviceChain(paymentReplyRetryInterceptor);
    return factory;
  }
}
```

- [ ] **Step 2: Update `InventoryReservedListener`'s container factory reference**

In `services/orders/src/main/java/com/microwave/orders/inventory/InventoryReservedListener.java`, change:

```java
  @RabbitListener(queues = RabbitMQConfig.INVENTORY_RESERVED_QUEUE, containerFactory = "rabbitListenerContainerFactory")
```

to:

```java
  @RabbitListener(queues = RabbitMQConfig.INVENTORY_RESERVED_QUEUE, containerFactory = "inventoryReplyListenerContainerFactory")
```

- [ ] **Step 3: Confirm the module compiles**

Run: `cd services/orders && mvn -B compile`
Expected: BUILD SUCCESS (full behavioral verification happens once Tasks 12-16 restore the listener's dependents — `OrderServiceTest`/`InventoryReservedListenerIT` will fail to compile until Task 14/16, since `OrderService`'s constructor still expects the old `PaymentsClient` at this point; that's expected and resolved by Task 14)

- [ ] **Step 4: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/config/RabbitMQConfig.java services/orders/src/main/java/com/microwave/orders/inventory/InventoryReservedListener.java
git commit -m "refactor(orders): split RabbitMQConfig into per-queue retry interceptors, add payments.exchange

Same fix as inventory's Task 7 — a shared recoverer hardcoded one
routing key, which would misroute the new payment-reply queue's
dead letters. Also declares payments.exchange defensively ahead of
Task 13's ChargePayment publisher."
```

---

## Task 12: `orders` — `ReservationCommandPublisher.sendReleaseStock`

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/inventory/messaging/ReleaseStockCommand.java`
- Modify: `services/orders/src/main/java/com/microwave/orders/inventory/ReservationCommandPublisher.java`
- Modify: `services/orders/src/test/java/com/microwave/orders/inventory/ReservationCommandPublisherIT.java`

**Interfaces:**
- Produces: `ReservationCommandPublisher.sendReleaseStock(Long orderId)` — consumed by Task 14's `OrderService.handlePaymentProcessed`.

- [ ] **Step 1: Create the message DTO**

`services/orders/src/main/java/com/microwave/orders/inventory/messaging/ReleaseStockCommand.java`:

```java
package com.microwave.orders.inventory.messaging;

public record ReleaseStockCommand(Long orderId) {
}
```

- [ ] **Step 2: Write the failing integration test**

Add to `services/orders/src/test/java/com/microwave/orders/inventory/ReservationCommandPublisherIT.java` — add the import `import com.microwave.orders.inventory.messaging.ReleaseStockCommand;`, add a second test-queue constant and bind it in `@BeforeEach`, and add the new test method:

```java
  private static final String TEST_RELEASE_QUEUE = "test.inventory.release-stock.queue";
```

```java
  @BeforeEach
  void bindTestCommandQueue() {
    Queue queue = new Queue(TEST_COMMAND_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RESERVE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);

    Queue releaseQueue = new Queue(TEST_RELEASE_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(releaseQueue);
    Binding releaseBinding = BindingBuilder.bind(releaseQueue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(releaseBinding);
  }
```

```java
  @Test
  void publishesReleaseStockCommand() {
    reservationCommandPublisher.sendReleaseStock(77L);

    ReleaseStockCommand received =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(TEST_RELEASE_QUEUE, 10000);

    assertThat(received).isNotNull();
    assertThat(received.orderId()).isEqualTo(77L);
  }
```

(This replaces the existing single-binding `@BeforeEach` method with the version above — don't add a second `@BeforeEach` method.)

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/orders && mvn -B verify -Dit.test=ReservationCommandPublisherIT`
Expected: FAIL to compile — `sendReleaseStock` doesn't exist yet.

- [ ] **Step 4: Implement `sendReleaseStock`**

In `services/orders/src/main/java/com/microwave/orders/inventory/ReservationCommandPublisher.java`, add the import `import com.microwave.orders.inventory.messaging.ReleaseStockCommand;` and this method:

```java
  public void sendReleaseStock(Long orderId) {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.INVENTORY_EXCHANGE, RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY,
        new ReleaseStockCommand(orderId));
  }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/orders && mvn -B verify -Dit.test=ReservationCommandPublisherIT`
Expected: PASS (both `publishesReserveStockCommand` and the new `publishesReleaseStockCommand`)

- [ ] **Step 6: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/inventory/messaging/ReleaseStockCommand.java services/orders/src/main/java/com/microwave/orders/inventory/ReservationCommandPublisher.java services/orders/src/test/java/com/microwave/orders/inventory/ReservationCommandPublisherIT.java
git commit -m "feat(orders): add ReservationCommandPublisher.sendReleaseStock"
```

---

## Task 13: `orders` — `PaymentCommandPublisher`

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/payments/messaging/ChargePaymentCommand.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/messaging/PaymentProcessedReply.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentCommandPublisher.java`
- Test: `services/orders/src/test/java/com/microwave/orders/payments/PaymentCommandPublisherIT.java`

**Interfaces:**
- Produces: `PaymentCommandPublisher.sendChargePayment(Long orderId, BigDecimal amount)` — consumed by Task 14's `OrderService.handleInventoryReserved`.

- [ ] **Step 1: Create the message DTOs**

`services/orders/src/main/java/com/microwave/orders/payments/messaging/ChargePaymentCommand.java`:

```java
package com.microwave.orders.payments.messaging;

import java.math.BigDecimal;

public record ChargePaymentCommand(Long orderId, BigDecimal amount) {
}
```

`services/orders/src/main/java/com/microwave/orders/payments/messaging/PaymentProcessedReply.java` (consumer side — bare record, no factories, mirroring orders' local copy of `InventoryReservedReply`):

```java
package com.microwave.orders.payments.messaging;

public record PaymentProcessedReply(Long orderId, boolean approved, String reason) {
}
```

- [ ] **Step 2: Write the failing integration test**

Create `services/orders/src/test/java/com/microwave/orders/payments/PaymentCommandPublisherIT.java`:

```java
package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.payments.messaging.ChargePaymentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PaymentCommandPublisherIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_COMMAND_QUEUE = "test.payments.charge-payment.queue";

  @Autowired
  private PaymentCommandPublisher paymentCommandPublisher;

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @BeforeEach
  void bindTestCommandQueue() {
    Queue queue = new Queue(TEST_COMMAND_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.PAYMENTS_EXCHANGE))
        .with(RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void publishesChargePaymentCommand() {
    paymentCommandPublisher.sendChargePayment(42L, new BigDecimal("150.00"));

    ChargePaymentCommand received =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(TEST_COMMAND_QUEUE, 10000);

    assertThat(received).isNotNull();
    assertThat(received.orderId()).isEqualTo(42L);
    assertThat(received.amount()).isEqualByComparingTo("150.00");
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/orders && mvn -B verify -Dit.test=PaymentCommandPublisherIT`
Expected: FAIL to compile — `PaymentCommandPublisher` doesn't exist yet.

- [ ] **Step 4: Implement `PaymentCommandPublisher`**

`services/orders/src/main/java/com/microwave/orders/payments/PaymentCommandPublisher.java`:

```java
package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.payments.messaging.ChargePaymentCommand;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaymentCommandPublisher {

  private final RabbitTemplate rabbitTemplate;

  public PaymentCommandPublisher(RabbitTemplate rabbitTemplate) {
    this.rabbitTemplate = rabbitTemplate;
  }

  public void sendChargePayment(Long orderId, BigDecimal amount) {
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.PAYMENTS_EXCHANGE, RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY,
        new ChargePaymentCommand(orderId, amount));
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd services/orders && mvn -B verify -Dit.test=PaymentCommandPublisherIT`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/payments/messaging services/orders/src/main/java/com/microwave/orders/payments/PaymentCommandPublisher.java services/orders/src/test/java/com/microwave/orders/payments/PaymentCommandPublisherIT.java
git commit -m "feat(orders): add PaymentCommandPublisher"
```

---

## Task 14: `orders` — rewrite `OrderService`, remove the Feign payments client

**Files:**
- Modify: `services/orders/src/main/java/com/microwave/orders/order/OrderService.java`
- Delete: `services/orders/src/main/java/com/microwave/orders/payments/PaymentsClient.java`
- Delete: `services/orders/src/main/java/com/microwave/orders/payments/PaymentStatus.java`
- Delete: `services/orders/src/main/java/com/microwave/orders/payments/rest/PaymentRequest.java`
- Delete: `services/orders/src/main/java/com/microwave/orders/payments/rest/PaymentResponse.java`
- Modify: `services/orders/src/main/java/com/microwave/orders/order/OrderController.java` (only if it references the deleted classes — see Step 1)
- Modify: `services/orders/src/main/resources/application.yml`
- Modify: `services/orders/src/test/java/com/microwave/orders/order/OrderServiceTest.java`

**Interfaces:**
- Consumes: `PaymentCommandPublisher.sendChargePayment` (Task 13), `ReservationCommandPublisher.sendReleaseStock` (Task 12).
- Produces: `OrderService.handlePaymentProcessed(PaymentProcessedReply)` — consumed by Task 15's `PaymentProcessedListener`. `OrderService`'s constructor signature changes (drops `PaymentsClient`, gains `PaymentCommandPublisher`).

- [ ] **Step 1: Confirm no other file references the classes being deleted**

Run: `grep -rn "PaymentsClient\|payments\.rest\.PaymentRequest\|payments\.rest\.PaymentResponse\|orders\.payments\.PaymentStatus" services/orders/src/main`
Expected: only matches inside `OrderService.java` (the file this task rewrites) and the 4 files being deleted. If `OrderController.java` or any other file appears, inspect it before proceeding — this plan assumes it doesn't (verified during planning).

- [ ] **Step 2: Rewrite the failing-first test — `OrderServiceTest.java`**

Replace the whole file `services/orders/src/test/java/com/microwave/orders/order/OrderServiceTest.java`:

```java
package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductResponse;
import com.microwave.orders.inventory.ReservationCommandPublisher;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import com.microwave.orders.payments.PaymentCommandPublisher;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock
  private OrderRepository orderRepository;

  @Mock
  private CatalogClient catalogClient;

  @Mock
  private OrderEventPublisher orderEventPublisher;

  @Mock
  private ReservationCommandPublisher reservationCommandPublisher;

  @Mock
  private PaymentCommandPublisher paymentCommandPublisher;

  private OrderService orderService;

  private static FeignException feignErrorWithStatus(int status) {
    Request request = Request.create(
        Request.HttpMethod.GET, "/products/1", Map.of(), null, StandardCharsets.UTF_8, null);
    Response response = Response.builder()
        .status(status)
        .request(request)
        .headers(Map.of())
        .build();
    return FeignException.errorStatus("Client#method", response);
  }

  private void initService() {
    orderService = new OrderService(
        orderRepository, catalogClient, orderEventPublisher, reservationCommandPublisher, paymentCommandPublisher);
  }

  @Test
  void createsOrderAndPublishesEventAndCommandWithoutTouchingPayments() {
    initService();
    when(catalogClient.getProduct(1L))
        .thenReturn(new ProductResponse(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
      Order persisted = invocation.getArgument(0);
      ReflectionTestUtils.setField(persisted, "id", 42L);
      return persisted;
    });

    Order order = orderService.createOrder(1L, 2);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
    verify(orderRepository, times(1)).save(any(Order.class));
    verifyNoInteractions(paymentCommandPublisher);
    verify(orderEventPublisher).publishOrderCreated(order);
    verify(reservationCommandPublisher).sendReserveStock(42L, 1L, 2);
  }

  @Test
  void throwsProductNotFoundAndCreatesNoOrder() {
    initService();
    when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(404));

    assertThatThrownBy(() -> orderService.createOrder(1L, 2))
        .isInstanceOf(ProductNotFoundException.class);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void throwsUpstreamUnavailableWhenCatalogFails() {
    initService();
    when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(500));

    assertThatThrownBy(() -> orderService.createOrder(1L, 2))
        .isInstanceOf(UpstreamServiceUnavailableException.class);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void sendsChargePaymentWhenReserved() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null));

    verify(paymentCommandPublisher).sendChargePayment(42L, new BigDecimal("200.00"));
    // Order stays CREATED — it only settles once handlePaymentProcessed runs.
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void rejectsOrderWhenNotReservedWithoutTouchingPayments() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, false, "OUT_OF_STOCK"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
    verifyNoInteractions(paymentCommandPublisher);
  }

  @Test
  void ignoresAnInventoryReplyForAnOrderThatAlreadyLeftCreated() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

    orderService.handleInventoryReserved(new InventoryReservedReply(42L, true, null));

    verifyNoInteractions(paymentCommandPublisher);
    verify(orderRepository, never()).save(any(Order.class));
  }

  @Test
  void throwsOrderNotFoundWhenInventoryReplyReferencesUnknownOrder() {
    initService();
    when(orderRepository.findById(99L)).thenReturn(Optional.empty());
    InventoryReservedReply reply = new InventoryReservedReply(99L, true, null);

    assertThatThrownBy(() -> orderService.handleInventoryReserved(reply))
        .isInstanceOf(OrderNotFoundException.class);
  }

  @Test
  void confirmsOrderWhenPaymentApproved() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    orderService.handlePaymentProcessed(new PaymentProcessedReply(42L, true, null));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    verifyNoInteractions(reservationCommandPublisher);
  }

  @Test
  void rejectsOrderAndReleasesStockWhenPaymentDeclined() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));
    when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

    orderService.handlePaymentProcessed(new PaymentProcessedReply(42L, false, "AMOUNT_EXCEEDS_LIMIT"));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
    verify(reservationCommandPublisher).sendReleaseStock(42L);
  }

  @Test
  void ignoresAPaymentProcessedReplyForAnOrderThatAlreadyLeftCreated() {
    initService();
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.REJECTED);
    ReflectionTestUtils.setField(order, "id", 42L);
    when(orderRepository.findById(42L)).thenReturn(Optional.of(order));

    orderService.handlePaymentProcessed(new PaymentProcessedReply(42L, true, null));

    verify(orderRepository, never()).save(any(Order.class));
    verifyNoInteractions(reservationCommandPublisher);
  }

  @Test
  void throwsOrderNotFoundWhenPaymentProcessedReplyReferencesUnknownOrder() {
    initService();
    when(orderRepository.findById(99L)).thenReturn(Optional.empty());
    PaymentProcessedReply reply = new PaymentProcessedReply(99L, true, null);

    assertThatThrownBy(() -> orderService.handlePaymentProcessed(reply))
        .isInstanceOf(OrderNotFoundException.class);
  }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd services/orders && mvn -B test -Dtest=OrderServiceTest`
Expected: FAIL to compile — `OrderService`'s constructor still takes `PaymentsClient`, and `handlePaymentProcessed` doesn't exist yet.

- [ ] **Step 4: Rewrite `OrderService.java`**

```java
package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductResponse;
import com.microwave.orders.inventory.ReservationCommandPublisher;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import com.microwave.orders.payments.PaymentCommandPublisher;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// Intentionally NOT @Transactional — persisting the order and the async
// side effects (publish/send) must not roll back together; see TD-1.
@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final CatalogClient catalogClient;
  private final OrderEventPublisher orderEventPublisher;
  private final ReservationCommandPublisher reservationCommandPublisher;
  private final PaymentCommandPublisher paymentCommandPublisher;

  public OrderService(
      OrderRepository orderRepository, CatalogClient catalogClient,
      OrderEventPublisher orderEventPublisher, ReservationCommandPublisher reservationCommandPublisher,
      PaymentCommandPublisher paymentCommandPublisher) {
    this.orderRepository = orderRepository;
    this.catalogClient = catalogClient;
    this.orderEventPublisher = orderEventPublisher;
    this.reservationCommandPublisher = reservationCommandPublisher;
    this.paymentCommandPublisher = paymentCommandPublisher;
  }

  // Returns immediately as CREATED — reservation/payment resolve async;
  // the client discovers the outcome via GET /orders/{id}.
  public Order createOrder(Long productId, int quantity) {
    ProductResponse product = fetchProduct(productId);
    BigDecimal totalAmount = product.price().multiply(BigDecimal.valueOf(quantity));

    Order order = orderRepository.save(new Order(productId, quantity, totalAmount, OrderStatus.CREATED));

    orderEventPublisher.publishOrderCreated(order);
    reservationCommandPublisher.sendReserveStock(order.getId(), productId, quantity);

    return order;
  }

  // Called by InventoryReservedListener. The CREATED check makes a sequential
  // redelivery (arriving after the first one already settled the order) a
  // no-op.
  public void handleInventoryReserved(InventoryReservedReply reply) {
    Order order = orderRepository.findById(reply.orderId())
        .orElseThrow(() -> new OrderNotFoundException(reply.orderId()));

    if (order.getStatus() != OrderStatus.CREATED) {
      return;
    }

    if (!reply.reserved()) {
      order.updateStatus(OrderStatus.REJECTED);
      orderRepository.save(order);
      return;
    }

    paymentCommandPublisher.sendChargePayment(order.getId(), order.getTotalAmount());
  }

  // Called by PaymentProcessedListener. Same CREATED guard as above — Order
  // stays CREATED for the whole window between the inventory reply and this
  // one, so the guard is valid for both reply handlers without a dedicated
  // intermediate status (see the Phase 4 design spec's "Order status model").
  public void handlePaymentProcessed(PaymentProcessedReply reply) {
    Order order = orderRepository.findById(reply.orderId())
        .orElseThrow(() -> new OrderNotFoundException(reply.orderId()));

    if (order.getStatus() != OrderStatus.CREATED) {
      return;
    }

    if (reply.approved()) {
      order.updateStatus(OrderStatus.CONFIRMED);
      orderRepository.save(order);
      return;
    }

    order.updateStatus(OrderStatus.REJECTED);
    orderRepository.save(order);
    reservationCommandPublisher.sendReleaseStock(order.getId());
  }

  private ProductResponse fetchProduct(Long productId) {
    try {
      return catalogClient.getProduct(productId);
    } catch (FeignException ex) {
      if (ex.status() == 404) {
        throw new ProductNotFoundException(productId);
      }
      throw new UpstreamServiceUnavailableException("catalog", ex);
    }
  }

  public Order findById(Long id) {
    return orderRepository.findById(id)
        .orElseThrow(() -> new OrderNotFoundException(id));
  }

  public List<Order> findAll() {
    return orderRepository.findAll();
  }
}
```

- [ ] **Step 5: Delete the Feign client and its DTOs**

```bash
git rm services/orders/src/main/java/com/microwave/orders/payments/PaymentsClient.java
git rm services/orders/src/main/java/com/microwave/orders/payments/PaymentStatus.java
git rm services/orders/src/main/java/com/microwave/orders/payments/rest/PaymentRequest.java
git rm services/orders/src/main/java/com/microwave/orders/payments/rest/PaymentResponse.java
```

- [ ] **Step 6: Remove the now-unused `payments.service.url` property**

In `services/orders/src/main/resources/application.yml`, delete this block (it configured `PaymentsClient`'s Feign URL, which no longer exists):

```yaml
payments:
  service:
    url: http://localhost:8082
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd services/orders && mvn -B test -Dtest=OrderServiceTest`
Expected: PASS (all 11 tests)

- [ ] **Step 8: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/order/OrderService.java services/orders/src/main/resources/application.yml services/orders/src/test/java/com/microwave/orders/order/OrderServiceTest.java
git commit -m "refactor(orders): replace synchronous payments call with ChargePayment/PaymentProcessed messaging"
```

---

## Task 15: `orders` — `PaymentProcessedListener`

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentProcessedListener.java`
- Test: `services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerIT.java`

**Interfaces:**
- Consumes: `RabbitMQConfig.{PAYMENT_PROCESSED_QUEUE, paymentReplyListenerContainerFactory}` (Task 11), `OrderService.handlePaymentProcessed(PaymentProcessedReply)` (Task 14).

- [ ] **Step 1: Write the failing integration test**

Create `services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerIT.java`:

```java
package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.ReleaseStockCommand;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentProcessedListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_RELEASE_STOCK_QUEUE = "test.inventory.release-stock.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private OrderRepository orderRepository;

  @BeforeEach
  void bindTestReleaseStockQueue() {
    Queue queue = new Queue(TEST_RELEASE_STOCK_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.INVENTORY_EXCHANGE))
        .with(RabbitMQConfig.RELEASE_STOCK_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void confirmsOrderWhenApproved() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(order.getId(), true, null));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });
  }

  @Test
  void rejectsOrderAndReleasesStockWhenDeclined() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(order.getId(), false, "AMOUNT_EXCEEDS_LIMIT"));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    });

    ReleaseStockCommand command =
        (ReleaseStockCommand) rabbitTemplate.receiveAndConvert(TEST_RELEASE_STOCK_QUEUE, 10000);
    assertThat(command).isNotNull();
    assertThat(command.orderId()).isEqualTo(order.getId());
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd services/orders && mvn -B verify -Dit.test=PaymentProcessedListenerIT`
Expected: FAIL to compile — `PaymentProcessedListener` doesn't exist yet.

- [ ] **Step 3: Implement `PaymentProcessedListener`**

`services/orders/src/main/java/com/microwave/orders/payments/PaymentProcessedListener.java`:

```java
package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.order.OrderService;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentProcessedListener {

  private final OrderService orderService;

  public PaymentProcessedListener(OrderService orderService) {
    this.orderService = orderService;
  }

  @RabbitListener(queues = RabbitMQConfig.PAYMENT_PROCESSED_QUEUE, containerFactory = "paymentReplyListenerContainerFactory")
  public void handle(PaymentProcessedReply reply) {
    orderService.handlePaymentProcessed(reply);
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd services/orders && mvn -B verify -Dit.test=PaymentProcessedListenerIT`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/payments/PaymentProcessedListener.java services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerIT.java
git commit -m "feat(orders): add PaymentProcessed RabbitMQ listener"
```

---

## Task 16: `orders` — resilience tests for `PaymentProcessedListener`

**Files:**
- Test: `services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerResilienceIT.java`

**Interfaces:**
- Consumes: everything from Tasks 11-15 (no production code changes in this task).

- [ ] **Step 1: Write the duplicate-delivery and dead-letter tests**

Create `services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerResilienceIT.java`:

```java
package com.microwave.orders.payments;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import com.microwave.orders.payments.messaging.PaymentProcessedReply;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class PaymentProcessedListenerResilienceIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private OrderRepository orderRepository;

  @Test
  void isIdempotentForADuplicateReply() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));
    PaymentProcessedReply reply = new PaymentProcessedReply(order.getId(), true, null);

    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });

    // A second delivery of the same reply hits the CREATED guard (the order
    // already left CREATED) and is a no-op — status stays CONFIRMED, not
    // reprocessed.
    rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY, reply);

    await().pollDelay(2, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });
  }

  @Test
  void deadLettersAMessageThatAlwaysFailsToProcess() {
    // No Order exists for this orderId, so OrderService.handlePaymentProcessed
    // always throws OrderNotFoundException — guaranteed to exhaust all 3
    // retries and land on the dead-letter queue.
    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.PAYMENT_PROCESSED_ROUTING_KEY,
        new PaymentProcessedReply(999999L, true, null));

    PaymentProcessedReply deadLettered =
        (PaymentProcessedReply) rabbitTemplate.receiveAndConvert(RabbitMQConfig.PAYMENT_PROCESSED_DLQ, 15000);
    assertThat(deadLettered).isNotNull();
    assertThat(deadLettered.orderId()).isEqualTo(999999L);
  }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `cd services/orders && mvn -B verify -Dit.test=PaymentProcessedListenerResilienceIT`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add services/orders/src/test/java/com/microwave/orders/payments/PaymentProcessedListenerResilienceIT.java
git commit -m "test(orders): cover PaymentProcessed idempotency and dead-lettering"
```

---

## Task 17: `orders` — update `InventoryReservedListenerIT`, delete `PaymentsClientIT`

**Files:**
- Modify: `services/orders/src/test/java/com/microwave/orders/inventory/InventoryReservedListenerIT.java`
- Delete: `services/orders/src/test/java/com/microwave/orders/payments/PaymentsClientIT.java`

**Interfaces:** none (test-only changes).

- [ ] **Step 1: Rewrite `InventoryReservedListenerIT.java`**

The `reserved=true` branch no longer calls `payments` synchronously via WireMock — it now publishes `ChargePaymentCommand`. Replace the whole file:

```java
package com.microwave.orders.inventory;

import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import com.microwave.orders.payments.messaging.ChargePaymentCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class InventoryReservedListenerIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  @ServiceConnection
  static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:4-management-alpine");

  private static final String TEST_CHARGE_PAYMENT_QUEUE = "test.payments.charge-payment.queue";

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private RabbitAdmin rabbitAdmin;

  @Autowired
  private OrderRepository orderRepository;

  @BeforeEach
  void bindTestChargePaymentQueue() {
    Queue queue = new Queue(TEST_CHARGE_PAYMENT_QUEUE, true, false, true);
    rabbitAdmin.declareQueue(queue);
    Binding binding = BindingBuilder.bind(queue)
        .to(new DirectExchange(RabbitMQConfig.PAYMENTS_EXCHANGE))
        .with(RabbitMQConfig.CHARGE_PAYMENT_ROUTING_KEY);
    rabbitAdmin.declareBinding(binding);
  }

  @Test
  void sendsChargePaymentWhenReserved() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY,
        new InventoryReservedReply(order.getId(), true, null));

    ChargePaymentCommand command =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(TEST_CHARGE_PAYMENT_QUEUE, 10000);
    assertThat(command).isNotNull();
    assertThat(command.orderId()).isEqualTo(order.getId());
    assertThat(command.amount()).isEqualByComparingTo("200.00");

    Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
  }

  @Test
  void rejectsOrderWhenNotReservedWithoutTouchingPayments() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY,
        new InventoryReservedReply(order.getId(), false, "OUT_OF_STOCK"));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    });

    ChargePaymentCommand command =
        (ChargePaymentCommand) rabbitTemplate.receiveAndConvert(TEST_CHARGE_PAYMENT_QUEUE, 2000);
    assertThat(command).isNull();
  }
}
```

- [ ] **Step 2: Delete `PaymentsClientIT.java` and its now-empty package**

```bash
git rm services/orders/src/test/java/com/microwave/orders/payments/PaymentsClientIT.java
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `cd services/orders && mvn -B verify -Dit.test=InventoryReservedListenerIT`
Expected: PASS

- [ ] **Step 4: Run the full orders test suite**

Run: `cd services/orders && mvn -B verify`
Expected: PASS (all unit + integration tests across the whole module)

- [ ] **Step 5: Commit**

```bash
git add services/orders/src/test/java/com/microwave/orders/inventory/InventoryReservedListenerIT.java
git rm services/orders/src/test/java/com/microwave/orders/payments/PaymentsClientIT.java
git commit -m "test(orders): update InventoryReservedListenerIT for async payment dispatch, drop PaymentsClientIT"
```

---

## Task 18: `orders` — `docker-compose.yml` cleanup

**Files:**
- Modify: `docker-compose.yml`

**Interfaces:** none (infrastructure only).

- [ ] **Step 1: Remove `PAYMENTS_SERVICE_URL` and the `payments` dependency from the `orders` service block**

In `docker-compose.yml`, in the `orders:` service block, remove the `PAYMENTS_SERVICE_URL: http://payments:8082` line from `environment:`, and remove the `payments:` entry (with its `condition: service_healthy`) from `depends_on:` — `orders` no longer needs `payments` to be up before it starts, the same way it never depended on `inventory` being up (Phase 3's async pattern). The block should read:

```yaml
  orders:
    build: ./services/orders
    restart: unless-stopped
    ports:
      - "127.0.0.1:8083:8083"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://orders-db:5432/orders_db
      SPRING_DATASOURCE_USERNAME: orders
      SPRING_DATASOURCE_PASSWORD: orders
      CATALOG_SERVICE_URL: http://catalog:8081
      SPRING_RABBITMQ_HOST: rabbitmq
      SPRING_RABBITMQ_PORT: 5672
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      orders-db:
        condition: service_healthy
      catalog:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 30s
```

- [ ] **Step 2: Validate the compose file**

Run: `docker-compose config --quiet`
Expected: no output, exit code 0

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml
git commit -m "chore(orders): drop the direct payments dependency now that the call is async"
```

---

## Task 19: Documentation updates

**Files:**
- Modify: `docs/decision-log/tech-debts.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/architecture.md`

**Interfaces:** none (documentation only).

- [ ] **Step 1: Move `TD-1` and `TD-6` to `## Resolved` in `docs/decision-log/tech-debts.md`**

Remove the `TD-1` and `TD-6` entries from the `## Open` section, and add them under `## Resolved` (after the existing `TD-5` entry), each with a `**Resolved in:**` field:

```markdown
### TD-1 — Orders can get stuck in `CREATED` if `payments` is unreachable

**Introduced in:** Phase 1
**Where:** `orders` service, `POST /orders` flow

If the call to `payments` failed for a technical reason (service down, timeout — not a business rejection), the order was persisted with `status=CREATED` and never moved to `CONFIRMED` or `REJECTED`. There was no retry, no saga, no compensation.

**Why it existed:** Phase 1 was scoped to synchronous REST only, deliberately, to focus on service boundaries and API contracts before introducing messaging.

**Resolved in:** Phase 4, by replacing the synchronous `orders` → `payments` REST call with a `ChargePayment`/`PaymentProcessed` RabbitMQ command/reply. An unreachable `payments` no longer strands the order — the command waits in `payments.charge-payment.queue` until `payments` is back up and consumes it, and RabbitMQ's own retry/redelivery covers processing failures once it does.

### TD-6 — Reservations aren't released when payment is declined after a successful reservation

**Introduced in:** Phase 3
**Where:** `orders`' `OrderService`, `inventory`'s `Reservation`

If `inventory` successfully reserved stock but the subsequent call to `payments` was declined, the order was correctly marked `REJECTED` — but the `Reservation` stayed `RESERVED` and the underlying `Stock` stayed decremented. Nothing released it.

**Why it existed:** compensation (a `ReleaseStock` command back to `inventory`) only makes sense once `payments` itself is commanded asynchronously, matching the same saga pattern — that was explicitly Phase 4's scope, not Phase 3's.

**Resolved in:** Phase 4, by adding a `ReleaseStock` command (`orders` → `inventory`, fire-and-forget) sent whenever `OrderService.handlePaymentProcessed` sees a declined `PaymentProcessedReply`. `ReservationService.release` restores `Stock` and marks the `Reservation` `RELEASED`, idempotently.
```

- [ ] **Step 2: Extend `TD-7`'s "Where" list in `docs/decision-log/tech-debts.md`**

In the existing `TD-7` entry (still `## Open`), change the "Where" line to add the three new dead-letter destinations:

```markdown
**Where:** `inventory`'s RabbitMQ consumers (`ReserveStock`, `ReleaseStock` commands), `orders`' RabbitMQ consumers (`InventoryReserved`, `PaymentProcessed` replies), `payments`' RabbitMQ consumer (`ChargePayment` command), `notifications`'s Kafka consumer (`OrderCreated` event), plus their respective dead-letter destinations.
```

And append a sentence to the entry's body (after the existing paragraph):

```markdown
Phase 4 added three more dead-letter destinations (`payments.charge-payment.dlq`, `orders.payment-reply.dlq`, `inventory.release-stock.dlq`) — same unmonitored-DLQ gap, not a new failure mode.
```

- [ ] **Step 3: Extend `TD-9`'s "Where" list in `docs/decision-log/tech-debts.md`**

In the existing `TD-9` entry (still `## Open`), change the "Where" line:

```markdown
**Where:** `orders`' `OrderService.createOrder`, `OrderService.handleInventoryReserved` (via `PaymentCommandPublisher.sendChargePayment`), `OrderService.handlePaymentProcessed` (via `ReservationCommandPublisher.sendReleaseStock`)
```

And append a sentence to the entry's body:

```markdown
Phase 4 added two more unguarded publish call sites (`ChargePayment`, `ReleaseStock`) — they inherit the same gap `ReserveStock` already had, not a new one.
```

- [ ] **Step 4: Mark Phase 4 complete in `docs/roadmap.md`**

Change the Phase 4 section's status line from (no status line currently present — it's the only phase left without one) to match the pattern of the other completed phases. Add, right after the `### Phase 4 — Payments moves to asynchronous messaging` heading:

```markdown
**Status:** Complete (2026-08-21). See [`docs/superpowers/specs/2026-08-21-phase4-payments-messaging-design.md`](superpowers/specs/2026-08-21-phase4-payments-messaging-design.md) and [`docs/superpowers/plans/2026-08-21-phase4-payments-messaging.md`](superpowers/plans/2026-08-21-phase4-payments-messaging.md) for the design and plan it was built from.
```

(Adjust the date if implementation finishes on a different day than this plan was written.)

Also update the "Next step" paragraph at the bottom of the file to append a sentence for Phase 4, following the exact pattern already used for Phases 1-3.1, and note that Phase 5 (Kubernetes orchestration) is next.

- [ ] **Step 5: Update the "Current architecture" diagram in `docs/architecture.md`**

In the `Current architecture (as of Phase 3)` section, rename it to `Current architecture (as of Phase 4)`, change the mermaid edge:

```
    Orders -->|REST, sync| Payments
```

to:

```
    Orders -->|"RabbitMQ command/reply"| Payments
```

And update the bullet list below the diagram — change:

```markdown
- `orders` → `payments` and `orders` → `catalog` are still synchronous REST, unchanged since Phase 1.
```

to:

```markdown
- `orders` → `catalog` is still synchronous REST, unchanged since Phase 1.
- `orders` → `payments` is a RabbitMQ command/reply (`ChargePayment`/`PaymentProcessed`), same pattern as `orders` → `inventory` since Phase 3. `orders` also sends a fire-and-forget `ReleaseStock` command to `inventory` when a payment is declined after a successful reservation.
```

And in the "Target architecture" section's explanatory list below its own diagram, update this line (it currently says the edge doesn't exist yet):

```markdown
- **Orders → Payments (RabbitMQ command/reply, replacing the REST call)**: Phase 4. Exact Kafka event names/schemas (e.g. an order-confirmed or payment-outcome event) are Phase 3/4 design-spec detail, not fixed by this document.
```

to:

```markdown
- **Orders → Payments (RabbitMQ command/reply, replacing the REST call)**: Phase 4, complete.
```

- [ ] **Step 6: Commit**

```bash
git add docs/decision-log/tech-debts.md docs/roadmap.md docs/architecture.md
git commit -m "docs: mark Phase 4 complete, resolve TD-1 and TD-6, extend TD-7 and TD-9"
```

---

## Final verification

- [ ] **Run the full test suite for all three changed services**

```bash
cd services/payments && mvn -B verify && cd ../inventory && mvn -B verify && cd ../orders && mvn -B verify
```

Expected: BUILD SUCCESS for all three.

- [ ] **Validate docker-compose and confirm the stack still boots**

```bash
docker-compose config --quiet
docker-compose up -d --build
docker-compose ps
```

Expected: all services report healthy. Manually verify the end-to-end flow once (per the existing manual checklist referenced by `TD-8`): create an order for an amount ≤ 10000 and confirm it reaches `CONFIRMED`; create one for an amount > 10000 and confirm it reaches `REJECTED` with `GET /inventory/reservations/{orderId}` showing `RELEASED`.

```bash
docker-compose down
```
