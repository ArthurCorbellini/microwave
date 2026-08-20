package com.microwave.orders.payments;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.microwave.orders.order.OrderRepository;
import com.microwave.orders.payments.rest.PaymentRequest;
import com.microwave.orders.payments.rest.PaymentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class PaymentsClientIT {

  static final WireMockServer wireMockServer = new WireMockServer(0);

  @DynamicPropertySource
  static void configurePaymentsUrl(DynamicPropertyRegistry registry) {
    wireMockServer.start();
    registry.add("payments.service.url", () -> "http://localhost:" + wireMockServer.port());
  }

  @Autowired
  private PaymentsClient paymentsClient;

  @MockitoBean
  private OrderRepository orderRepository;

  @AfterEach
  void resetWireMock() {
    wireMockServer.resetAll();
  }

  @Test
  void chargesPaymentThroughPaymentsService() {
    wireMockServer.stubFor(post(urlEqualTo("/payments"))
        .willReturn(okJson("""
            {"id":1,"orderId":42,"amount":100.00,"status":"APPROVED"}
            """)));

    PaymentResponse response = paymentsClient.charge(new PaymentRequest(42L, new BigDecimal("100.00")));

    assertThat(response.status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(response.amount()).isEqualByComparingTo("100.00");

    // Also pin the outgoing contract: payments' PaymentRequest.orderId is @NotNull,
    // so a broken serialization would surface there as a 400, not here.
    wireMockServer.verify(postRequestedFor(urlEqualTo("/payments"))
        .withRequestBody(equalToJson("""
            {"orderId":42,"amount":100.00}
            """)));
  }
}
