package com.microwave.orders.inventory;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.microwave.orders.config.RabbitMQConfig;
import com.microwave.orders.inventory.messaging.InventoryReservedReply;
import com.microwave.orders.order.Order;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.order.OrderStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
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

  static final WireMockServer wireMockServer = new WireMockServer(0);

  @DynamicPropertySource
  static void configurePaymentsUrl(DynamicPropertyRegistry registry) {
    wireMockServer.start();
    registry.add("payments.service.url", () -> "http://localhost:" + wireMockServer.port());
  }

  @Autowired
  private RabbitTemplate rabbitTemplate;

  @Autowired
  private OrderRepository orderRepository;

  @AfterEach
  void resetWireMock() {
    wireMockServer.resetAll();
  }

  @Test
  void confirmsOrderWhenReservedAndPaymentApproved() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));
    wireMockServer.stubFor(post(urlEqualTo("/payments"))
        .willReturn(okJson("""
            {"id":1,"orderId":%d,"amount":200.00,"status":"APPROVED"}
            """.formatted(order.getId()))));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY,
        new InventoryReservedReply(order.getId(), true, null));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    });
  }

  @Test
  void rejectsOrderWhenNotReservedWithoutCallingPayments() {
    Order order = orderRepository.save(new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CREATED));

    rabbitTemplate.convertAndSend(
        RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.INVENTORY_RESERVED_ROUTING_KEY,
        new InventoryReservedReply(order.getId(), false, "OUT_OF_STOCK"));

    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
      Order reloaded = orderRepository.findById(order.getId()).orElseThrow();
      assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.REJECTED);
    });
    wireMockServer.verify(0, postRequestedFor(urlEqualTo("/payments")));
  }
}
