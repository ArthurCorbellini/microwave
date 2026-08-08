package com.microwave.payments.payment;

import com.microwave.payments.payment.enums.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
  private PaymentRepository paymentRepository;

  @Test
  void approvesPaymentWithinLimit() throws Exception {
    Payment saved = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
    when(paymentRepository.save(any(Payment.class))).thenReturn(saved);

    mockMvc.perform(post("/payments")
            .contentType("application/json")
            .content("""
                {"orderId":1,"amount":100.00}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("APPROVED"));

    // Assert on the Payment actually handed to the repository, not on what the
    // stub was told to return — otherwise the test only proves the stub echoes back.
    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(captor.getValue().getOrderId()).isEqualTo(1L);
    assertThat(captor.getValue().getAmount()).isEqualByComparingTo("100.00");
  }

  @Test
  void rejectsPaymentAboveLimit() throws Exception {
    Payment saved = new Payment(2L, new BigDecimal("15000.00"), PaymentStatus.REJECTED);
    when(paymentRepository.save(any(Payment.class))).thenReturn(saved);

    mockMvc.perform(post("/payments")
            .contentType("application/json")
            .content("""
                {"orderId":2,"amount":15000.00}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("REJECTED"));

    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).save(captor.capture());
    assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.REJECTED);
    assertThat(captor.getValue().getOrderId()).isEqualTo(2L);
    assertThat(captor.getValue().getAmount()).isEqualByComparingTo("15000.00");
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
    when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

    mockMvc.perform(get("/payments/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  void returnsNotFoundForMissingPayment() throws Exception {
    when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

    mockMvc.perform(get("/payments/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Payment not found for order: 99"))
        .andExpect(jsonPath("$.instance").value("/payments/99"));
  }
}
