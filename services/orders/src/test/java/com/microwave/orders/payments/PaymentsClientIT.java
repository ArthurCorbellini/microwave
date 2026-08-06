package com.microwave.orders.payments;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PaymentsClientIT {

    static final WireMockServer wireMockServer = new WireMockServer(0);

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void configurePaymentsUrl(DynamicPropertyRegistry registry) {
        wireMockServer.start();
        registry.add("payments.service.url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentsClient paymentsClient;

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

        PaymentResponseDto response = paymentsClient.charge(new PaymentRequestDto(42L, new BigDecimal("100.00")));

        assertThat(response.status()).isEqualTo(PaymentStatusDto.APPROVED);
        assertThat(response.amount()).isEqualByComparingTo("100.00");
    }
}
