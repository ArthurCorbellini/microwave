package com.microwave.payments.payment;

import com.microwave.payments.payment.rest.PaymentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @Operation(summary = "Get a payment by order ID")
  @ApiResponse(responseCode = "200", description = "Payment found")
  @ApiResponse(responseCode = "404", description = "No payment exists for that order",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping("/{orderId}")
  public PaymentResponse getByOrderId(@PathVariable Long orderId) {
    return PaymentResponse.from(paymentService.findByOrderId(orderId));
  }
}
