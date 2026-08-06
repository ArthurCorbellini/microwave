package com.microwave.orders.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.microwave.orders.order.OrderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class CatalogClientIT {

    static final WireMockServer wireMockServer = new WireMockServer(0);

    @DynamicPropertySource
    static void configureCatalogUrl(DynamicPropertyRegistry registry) {
        wireMockServer.start();
        registry.add("catalog.service.url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private CatalogClient catalogClient;

    @MockitoBean
    private OrderRepository orderRepository;

    @AfterEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    void fetchesProductFromCatalogService() {
        wireMockServer.stubFor(get(urlEqualTo("/products/1"))
                .willReturn(okJson("""
                        {"id":1,"name":"Keyboard","description":"Mechanical keyboard","price":350.00}
                        """)));

        ProductDto product = catalogClient.getProduct(1L);

        assertThat(product.name()).isEqualTo("Keyboard");
        assertThat(product.price()).isEqualByComparingTo("350.00");
    }
}
