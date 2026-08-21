package com.microwave.catalog.product;

import com.microwave.catalog.product.rest.ProductRequest;
import com.microwave.catalog.product.rest.ProductResponse;
import com.microwave.catalog.error.ValidationProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@RequestMapping("/products")
public class ProductController {

  private final ProductService productService;

  public ProductController(ProductService productService) {
    this.productService = productService;
  }

  @Operation(summary = "List all products")
  @ApiResponse(responseCode = "200", description = "Products listed successfully")
  @GetMapping
  public List<ProductResponse> listProducts() {
    return productService.findAll().stream()
        .map(ProductResponse::from)
        .toList();
  }

  @Operation(summary = "Get a product by ID")
  @ApiResponse(responseCode = "200", description = "Product found")
  @ApiResponse(responseCode = "404", description = "Product not found",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @GetMapping("/{id}")
  public ProductResponse getProduct(@PathVariable Long id) {
    return ProductResponse.from(productService.findById(id));
  }

  @Operation(summary = "Create a new product")
  @ApiResponse(responseCode = "201", description = "Product created successfully")
  @ApiResponse(responseCode = "400", description = "Validation failure",
      content = @Content(schema = @Schema(implementation = ValidationProblemDetail.class)))
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
    Product saved = productService.createProduct(request.name(), request.description(), request.price());
    return ProductResponse.from(saved);
  }
}
