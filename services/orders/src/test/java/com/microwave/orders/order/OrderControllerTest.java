package com.microwave.orders.order;

import com.microwave.orders.order.enums.OrderStatus;
import com.microwave.orders.order.exceptions.OrderNotFoundException;
import com.microwave.orders.order.exceptions.ProductNotFoundException;
import com.microwave.orders.order.exceptions.UpstreamServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private OrderService orderService;

  @Test
  void createsOrder() throws Exception {
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
    when(orderService.createOrder(1L, 2)).thenReturn(order);

    mockMvc.perform(post("/orders")
            .contentType("application/json")
            .content("""
                {"productId":1,"quantity":2}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void rejectsOrderWithZeroQuantity() throws Exception {
    mockMvc.perform(post("/orders")
            .contentType("application/json")
            .content("""
                {"productId":1,"quantity":0}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.path").value("/orders"))
        .andExpect(jsonPath("$.timestamp").exists())
        .andExpect(jsonPath("$.message").exists());
  }

  @Test
  void getsOrderById() throws Exception {
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
    when(orderService.findById(1L)).thenReturn(order);

    mockMvc.perform(get("/orders/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));
  }

  @Test
  void listsOrders() throws Exception {
    Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
    when(orderService.findAll()).thenReturn(List.of(order));

    mockMvc.perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
  }

  @Test
  void returnsNotFoundForMissingOrder() throws Exception {
    when(orderService.findById(99L)).thenThrow(new OrderNotFoundException(99L));

    mockMvc.perform(get("/orders/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.path").value("/orders/99"));
  }

  @Test
  void returnsNotFoundWhenProductMissing() throws Exception {
    doThrow(new ProductNotFoundException(1L)).when(orderService).createOrder(1L, 2);

    mockMvc.perform(post("/orders")
            .contentType("application/json")
            .content("""
                {"productId":1,"quantity":2}
                """))
        .andExpect(status().isNotFound());
  }

  @Test
  void returnsServiceUnavailableWhenUpstreamFails() throws Exception {
    doThrow(new UpstreamServiceUnavailableException("payments", new RuntimeException("boom")))
        .when(orderService).createOrder(1L, 2);

    mockMvc.perform(post("/orders")
            .contentType("application/json")
            .content("""
                {"productId":1,"quantity":2}
                """))
        .andExpect(status().isServiceUnavailable());
  }
}
