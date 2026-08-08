package com.microwave.catalog.product;

import com.microwave.catalog.product.dto.ProductRequest;
import com.microwave.catalog.product.dto.ProductResponse;
import com.microwave.catalog.product.exceptions.ProductNotFoundException;
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
@RequestMapping("/products")
public class ProductController {

  private final ProductRepository productRepository;

  public ProductController(ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  @Operation(summary = "List all products")
  @ApiResponse(responseCode = "200", description = "Products listed successfully")
  @GetMapping
  public List<ProductResponse> listProducts() {
    return productRepository.findAll().stream()
        .map(ProductResponse::from)
        .toList();
  }

  @Operation(summary = "Get a product by ID")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Product found"),
      @ApiResponse(responseCode = "404", description = "Product not found",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @GetMapping("/{id}")
  public ProductResponse getProduct(@PathVariable Long id) {
    Product product = productRepository.findById(id)
        .orElseThrow(() -> new ProductNotFoundException(id));
    return ProductResponse.from(product);
  }

  @Operation(summary = "Create a new product")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Product created successfully"),
      @ApiResponse(responseCode = "400", description = "Validation failure",
          content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  })
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
    Product product = new Product(request.name(), request.description(), request.price());
    Product saved = productRepository.save(product);
    return ProductResponse.from(saved);
  }
}
