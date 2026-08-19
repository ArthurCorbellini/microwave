package com.microwave.catalog.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock
  private ProductRepository productRepository;

  private ProductService productService;

  private void initService() {
    productService = new ProductService(productRepository);
  }

  @Test
  void createsProduct() {
    initService();
    Product saved = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.save(any(Product.class))).thenReturn(saved);

    Product result = productService.createProduct("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));

    assertThat(result.getName()).isEqualTo("Keyboard");
  }

  @Test
  void findsProductById() {
    initService();
    Product product = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.findById(1L)).thenReturn(Optional.of(product));

    Product result = productService.findById(1L);

    assertThat(result.getName()).isEqualTo("Keyboard");
  }

  @Test
  void throwsWhenProductNotFound() {
    initService();
    when(productRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> productService.findById(99L))
        .isInstanceOf(ProductNotFoundException.class)
        .hasMessage("Product not found: 99");
  }

  @Test
  void findsAllProducts() {
    initService();
    Product product = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.findAll()).thenReturn(List.of(product));

    List<Product> result = productService.findAll();

    assertThat(result).hasSize(1);
  }
}
