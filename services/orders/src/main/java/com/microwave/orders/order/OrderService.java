package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductDto;
import com.microwave.orders.payments.PaymentRequestDto;
import com.microwave.orders.payments.PaymentResponseDto;
import com.microwave.orders.payments.PaymentStatusDto;
import com.microwave.orders.payments.PaymentsClient;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final PaymentsClient paymentsClient;

    public OrderService(OrderRepository orderRepository, CatalogClient catalogClient, PaymentsClient paymentsClient) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.paymentsClient = paymentsClient;
    }

    // Intentionally NOT @Transactional: the order created below must survive
    // even if the payments call that follows fails. See docs/tech-debt.md TD-1 —
    // wrapping this in a single transaction would roll back the CREATED order
    // whenever payments is unreachable, which is explicitly not what Phase 1 does.
    public Order createOrder(Long productId, int quantity) {
        ProductDto product = fetchProduct(productId);
        BigDecimal totalAmount = product.price().multiply(BigDecimal.valueOf(quantity));

        Order order = orderRepository.save(new Order(productId, quantity, totalAmount, OrderStatus.CREATED));

        PaymentResponseDto payment = requestPayment(order);

        order.updateStatus(payment.status() == PaymentStatusDto.APPROVED ? OrderStatus.CONFIRMED : OrderStatus.REJECTED);
        return orderRepository.save(order);
    }

    private ProductDto fetchProduct(Long productId) {
        try {
            return catalogClient.getProduct(productId);
        } catch (FeignException ex) {
            if (ex.status() == 404) {
                throw new ProductNotFoundException(productId);
            }
            throw new UpstreamServiceUnavailableException("catalog", ex);
        }
    }

    private PaymentResponseDto requestPayment(Order order) {
        try {
            return paymentsClient.charge(new PaymentRequestDto(order.getId(), order.getTotalAmount()));
        } catch (FeignException ex) {
            throw new UpstreamServiceUnavailableException("payments", ex);
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
