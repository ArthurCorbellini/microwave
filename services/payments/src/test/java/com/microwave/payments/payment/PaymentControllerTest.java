package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PaymentService paymentService;

  @Test
  void chargesPayment() throws Exception {
    Payment saved = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
    when(paymentService.charge(1L, new BigDecimal("100.00"))).thenReturn(saved);

    mockMvc.perform(post("/payments")
            .contentType("application/json")
            .content("""
                {"orderId":1,"amount":100.00}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void rejectsPaymentWithNullOrderId() throws Exception {
    mockMvc.perform(post("/payments")
            .contentType("application/json")
            .content("""
                {"amount":100.00}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.instance").value("/payments"))
        .andExpect(jsonPath("$.errors[0].field").value("orderId"))
        .andExpect(jsonPath("$.errors[0].message").value("must not be null"));
  }

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
