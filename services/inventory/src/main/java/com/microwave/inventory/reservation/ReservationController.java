package com.microwave.inventory.reservation;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/inventory/reservations")
public class ReservationController {

  private final ReservationService reservationService;

  public ReservationController(ReservationService reservationService) {
    this.reservationService = reservationService;
  }

  @Operation(summary = "Get the stock reservation for an order")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Reservation found"),
      @ApiResponse(responseCode = "404", description = "No reservation exists for that order",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{orderId}")
  public ReservationResponse getByOrderId(@PathVariable Long orderId) {
    return ReservationResponse.from(reservationService.findByOrderId(orderId));
  }
}
