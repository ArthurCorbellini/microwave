package com.microwave.payments.payment;

import com.microwave.payments.payment.rest.PaymentRequest;
import com.microwave.payments.payment.rest.PaymentResponse;
import com.microwave.payments.error.ValidationProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

  private final PaymentService paymentService;

  public PaymentController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  @Operation(summary = "Charge a payment",
      description = "Simulates processing a payment. Approves amounts up to 10000, rejects anything above.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Payment processed (approved or rejected)"),
      @ApiResponse(responseCode = "400", description = "Validation failure",
          content = @Content(schema = @Schema(implementation = ValidationProblemDetail.class)))
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentResponse charge(@Valid @RequestBody PaymentRequest request) {
    Payment payment = paymentService.charge(request.orderId(), request.amount());
    return PaymentResponse.from(payment);
  }

  @Operation(summary = "Get a payment by order ID")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Payment found"),
      @ApiResponse(responseCode = "404", description = "No payment exists for that order",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{orderId}")
  public PaymentResponse getByOrderId(@PathVariable Long orderId) {
    return PaymentResponse.from(paymentService.findByOrderId(orderId));
  }
}
