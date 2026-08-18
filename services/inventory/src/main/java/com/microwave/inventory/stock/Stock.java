package com.microwave.inventory.stock;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(name = "stock", uniqueConstraints = @UniqueConstraint(columnNames = "productId"))
public class Stock {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long productId;

  @Column(nullable = false)
  private int availableQuantity;

  @Version
  private Long version;

  protected Stock() {
  }

  public Stock(Long productId, int availableQuantity) {
    this.productId = productId;
    this.availableQuantity = availableQuantity;
  }

  public void decrease(int quantity) {
    this.availableQuantity -= quantity;
  }

  public Long getId() {
    return id;
  }

  public Long getProductId() {
    return productId;
  }

  public int getAvailableQuantity() {
    return availableQuantity;
  }

  public Long getVersion() {
    return version;
  }
}
