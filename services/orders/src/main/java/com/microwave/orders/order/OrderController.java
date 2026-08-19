package com.microwave.orders.order;

import com.microwave.orders.error.ValidationProblemDetail;
import com.microwave.orders.order.dto.OrderRequest;
import com.microwave.orders.order.dto.OrderResponse;
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

import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @Operation(summary = "Create a new order",
      description = "Fetches the product from catalog, persists the order as CREATED, and returns immediately — "
          + "the reservation/payment outcome is asynchronous. Poll GET /orders/{id} for the final status.")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Order created (status is CREATED — confirmation/rejection happens asynchronously)"),
      @ApiResponse(responseCode = "400", description = "Validation failure",
          content = @Content(schema = @Schema(implementation = ValidationProblemDetail.class))),
      @ApiResponse(responseCode = "404", description = "Product not found in catalog",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
      @ApiResponse(responseCode = "503", description = "catalog is unreachable",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
    Order order = orderService.createOrder(request.productId(), request.quantity());
    return OrderResponse.from(order);
  }

  @Operation(summary = "Get an order by ID")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Order found"),
      @ApiResponse(responseCode = "404", description = "Order not found",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{id}")
  public OrderResponse getOrder(@PathVariable Long id) {
    return OrderResponse.from(orderService.findById(id));
  }

  @Operation(summary = "List all orders")
  @ApiResponse(responseCode = "200", description = "Orders listed successfully")
  @GetMapping
  public List<OrderResponse> listOrders() {
    return orderService.findAll().stream()
        .map(OrderResponse::from)
        .toList();
  }
}
