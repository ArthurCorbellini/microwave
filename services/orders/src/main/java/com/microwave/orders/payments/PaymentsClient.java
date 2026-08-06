package com.microwave.orders.payments;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payments", url = "${payments.service.url}")
public interface PaymentsClient {

    @PostMapping("/payments")
    PaymentResponseDto charge(@RequestBody PaymentRequestDto request);
}
