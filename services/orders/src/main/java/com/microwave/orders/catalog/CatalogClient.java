package com.microwave.orders.catalog;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "catalog", url = "${catalog.service.url}")
public interface CatalogClient {

    @GetMapping("/products/{id}")
    ProductDto getProduct(@PathVariable("id") Long id);
}
