package com.microwave.catalog.product;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {

  private final ProductRepository productRepository;

  public ProductService(ProductRepository productRepository) {
    this.productRepository = productRepository;
  }

  public Product createProduct(String name, String description, BigDecimal price) {
    return productRepository.save(new Product(name, description, price));
  }

  public Product findById(Long id) {
    return productRepository.findById(id)
        .orElseThrow(() -> new ProductNotFoundException(id));
  }

  public List<Product> findAll() {
    return productRepository.findAll();
  }
}
