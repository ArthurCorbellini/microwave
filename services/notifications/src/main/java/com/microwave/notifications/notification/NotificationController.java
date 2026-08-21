package com.microwave.notifications.notification;

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
@RequestMapping("/notifications")
public class NotificationController {

  private final NotificationService notificationService;

  public NotificationController(NotificationService notificationService) {
    this.notificationService = notificationService;
  }

  @Operation(summary = "Get the notification logged for an order")
  @ApiResponse(responseCode = "200", description = "Notification found")
  @ApiResponse(responseCode = "404", description = "No notification exists for that order",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping("/{orderId}")
  public NotificationLogResponse getByOrderId(@PathVariable Long orderId) {
    return NotificationLogResponse.from(notificationService.findByOrderId(orderId));
  }
}
