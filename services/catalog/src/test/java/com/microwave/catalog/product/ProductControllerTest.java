package com.microwave.catalog.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private ProductRepository productRepository;

  @Test
  void createsProduct() throws Exception {
    Product saved = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.save(any(Product.class))).thenReturn(saved);

    mockMvc.perform(post("/products")
            .contentType("application/json")
            .content("""
                {"name":"Keyboard","description":"Mechanical keyboard","price":350.00}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Keyboard"))
        .andExpect(jsonPath("$.price").value(350.00));
  }

  @Test
  void rejectsProductWithBlankName() throws Exception {
    mockMvc.perform(post("/products")
            .contentType("application/json")
            .content("""
                {"name":"","description":"Mechanical keyboard","price":350.00}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.instance").value("/products"))
        .andExpect(jsonPath("$.errors[0].field").value("name"))
        .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
  }

  @Test
  void getsProductById() throws Exception {
    Product product = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.findById(1L)).thenReturn(Optional.of(product));

    mockMvc.perform(get("/products/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Keyboard"));
  }

  @Test
  void returnsNotFoundForMissingProduct() throws Exception {
    when(productRepository.findById(99L)).thenReturn(Optional.empty());

    mockMvc.perform(get("/products/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.title").value("Not Found"))
        .andExpect(jsonPath("$.detail").value("Product not found: 99"))
        .andExpect(jsonPath("$.instance").value("/products/99"));
  }

  @Test
  void listsProducts() throws Exception {
    Product product = new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00"));
    when(productRepository.findAll()).thenReturn(List.of(product));

    mockMvc.perform(get("/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Keyboard"));
  }
}
