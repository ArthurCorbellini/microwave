package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
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
                .andExpect(status().isNotFound());
    }
}
