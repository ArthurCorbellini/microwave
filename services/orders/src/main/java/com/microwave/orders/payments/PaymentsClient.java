package com.microwave.orders.payments;

import com.microwave.orders.payments.dto.PaymentRequest;
import com.microwave.orders.payments.dto.PaymentResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payments", url = "${payments.service.url}")
public interface PaymentsClient {

  @PostMapping("/payments")
  PaymentResponse charge(@RequestBody PaymentRequest request);
}
