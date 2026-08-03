# Phase 1 Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up `catalog`, `payments`, and `orders` as three independent Spring Boot services that together support creating an order end-to-end over synchronous REST, each with its own PostgreSQL database.

**Architecture:** Mono-repo under `services/`, one Maven module per service, no parent POM. `catalog` and `payments` are simple CRUD-style services (controller → repository). `orders` is the orchestrator: it calls `catalog` and `payments` via OpenFeign and owns the order-creation workflow.

**Tech Stack:** Java 25, Spring Boot 4.0.7, Spring Cloud 2025.1.2 (OpenFeign, `orders` only), Maven, PostgreSQL, JUnit 5, Testcontainers 2.0.5, Mockito, WireMock 3.13.2.

## Global Constraints

- Java 25 (LTS). Spring Boot pinned to 4.0.7 (not 4.1.x) because Spring Cloud 2025.1.2 — needed for OpenFeign in `orders` — targets 4.0.7; this is the latest release train with confirmed Spring Boot compatibility as of this writing.
- Maven, no parent POM — each service under `services/` builds independently.
- PostgreSQL, one database per service. All monetary fields (`price`, `amount`, `totalAmount`) are `BigDecimal`, never `double`/`float`.
- All code, comments, and docs are in English.
- OpenFeign is used only in `orders`. Service addresses are fixed URLs via `application.yml` config properties — no Eureka, no service discovery.
- Testing: JUnit 5 + Testcontainers for integration tests against a real Postgres; MockMvc for controller-layer tests; Mockito for service-layer unit tests with mocked collaborators.
- Error responses use a standardized body — `{timestamp, status, error, message, path}` — returned via `@RestControllerAdvice` in each service.
- Known, deliberate limitation: if `payments` is unreachable during order creation, the order stays persisted with `status=CREATED` and is not rolled back. This is documented as TD-1 in `docs/tech-debt.md` and must NOT be "fixed" as part of this plan — the tasks below implement it as specified, including the test that proves the order survives.
- Git: commit messages follow Conventional Commits (as already written in each task's commit step below); never add a `Co-Authored-By` line; never run `git push` unless explicitly asked in that message.

---

## catalog service

### Task 1: Catalog service scaffold

**Files:**
- Create: `services/catalog/pom.xml`
- Create: `services/catalog/src/main/java/com/microwave/catalog/CatalogApplication.java`
- Create: `services/catalog/src/main/resources/application.yml`
- Test: `services/catalog/src/test/java/com/microwave/catalog/CatalogApplicationTests.java`

**Interfaces:**
- Produces: `com.microwave.catalog.CatalogApplication` (Spring Boot entry point), service listens on port 8081

- [ ] **Step 1: Create the Maven project file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>

    <groupId>com.microwave</groupId>
    <artifactId>catalog</artifactId>
    <version>0.1.0</version>
    <name>catalog</name>
    <description>Product catalog service</description>

    <properties>
        <java.version>25</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the application config**

```yaml
server:
  port: 8081

spring:
  application:
    name: catalog
```

- [ ] **Step 3: Write the failing context-load test**

```java
package com.microwave.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CatalogApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/catalog/pom.xml test`
Expected: FAIL — no `@SpringBootConfiguration` found (no application class exists yet)

- [ ] **Step 5: Create the application entry point**

```java
package com.microwave.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CatalogApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/catalog/pom.xml test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/catalog/pom.xml services/catalog/src/main/java/com/microwave/catalog/CatalogApplication.java services/catalog/src/main/resources/application.yml services/catalog/src/test/java/com/microwave/catalog/CatalogApplicationTests.java
git commit -m "feat(catalog): scaffold Spring Boot service"
```

---

### Task 2: Catalog — Product entity and repository

**Files:**
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/Product.java`
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/ProductRepository.java`
- Modify: `services/catalog/pom.xml`
- Modify: `services/catalog/src/main/resources/application.yml`
- Test: `services/catalog/src/test/java/com/microwave/catalog/product/ProductRepositoryIT.java`

**Interfaces:**
- Produces: `Product(String name, String description, BigDecimal price)`, getters `getId()`, `getName()`, `getDescription()`, `getPrice()`; `ProductRepository extends JpaRepository<Product, Long>`

- [ ] **Step 1: Add JPA, Postgres, and Testcontainers dependencies to the POM**

Add inside `<dependencyManagement>` (create the element if it doesn't exist yet, as a sibling of `<dependencies>`):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>2.0.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add datasource and JPA config**

Append to `services/catalog/src/main/resources/application.yml`:

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/catalog_db
    username: catalog
    password: catalog
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

- [ ] **Step 3: Write the failing repository test**

```java
package com.microwave.catalog.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ProductRepositoryIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ProductRepository productRepository;

    @Test
    void savesAndFindsProduct() {
        Product saved = productRepository.save(
                new Product("Keyboard", "Mechanical keyboard", new BigDecimal("350.00")));

        Optional<Product> found = productRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Keyboard");
        assertThat(found.get().getPrice()).isEqualByComparingTo("350.00");
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductRepositoryIT`
Expected: FAIL — compile error, `Product` and `ProductRepository` don't exist yet

- [ ] **Step 5: Create the Product entity**

```java
package com.microwave.catalog.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private BigDecimal price;

    protected Product() {
    }

    public Product(String name, String description, BigDecimal price) {
        this.name = name;
        this.description = description;
        this.price = price;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getPrice() {
        return price;
    }
}
```

- [ ] **Step 6: Create the repository**

```java
package com.microwave.catalog.product;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
```

- [ ] **Step 7: Run the test and verify it passes**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductRepositoryIT`
Expected: PASS (requires Docker running locally, since Testcontainers starts a real Postgres container)

- [ ] **Step 8: Commit**

```bash
git add services/catalog/pom.xml services/catalog/src/main/resources/application.yml services/catalog/src/main/java/com/microwave/catalog/product/Product.java services/catalog/src/main/java/com/microwave/catalog/product/ProductRepository.java services/catalog/src/test/java/com/microwave/catalog/product/ProductRepositoryIT.java
git commit -m "feat(catalog): add Product entity and repository"
```

---

### Task 3: Catalog — Product REST endpoints

**Files:**
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/ProductRequest.java`
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/ProductResponse.java`
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/ProductController.java`
- Test: `services/catalog/src/test/java/com/microwave/catalog/product/ProductControllerTest.java`

**Interfaces:**
- Consumes: `Product`, `ProductRepository` (Task 2)
- Produces: `POST /products` (201), `GET /products/{id}` (200 or 404 via `ResponseStatusException`), `GET /products` (200)

- [ ] **Step 1: Write the failing controller test**

```java
package com.microwave.catalog.product;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
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
                .andExpect(status().isBadRequest());
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
                .andExpect(status().isNotFound());
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
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductControllerTest`
Expected: FAIL — compile error, `ProductController`, `ProductRequest`, `ProductResponse` don't exist yet

- [ ] **Step 3: Create the request DTO**

```java
package com.microwave.catalog.product;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String name,
        String description,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal price) {
}
```

- [ ] **Step 4: Create the response DTO**

```java
package com.microwave.catalog.product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, String description, BigDecimal price) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice());
    }
}
```

- [ ] **Step 5: Create the controller**

```java
package com.microwave.catalog.product;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductRepository productRepository;

    public ProductController(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<ProductResponse> listProducts() {
        return productRepository.findAll().stream()
                .map(ProductResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
        return ProductResponse.from(product);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse createProduct(@Valid @RequestBody ProductRequest request) {
        Product product = new Product(request.name(), request.description(), request.price());
        Product saved = productRepository.save(product);
        return ProductResponse.from(saved);
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductControllerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/catalog/src/main/java/com/microwave/catalog/product/ProductRequest.java services/catalog/src/main/java/com/microwave/catalog/product/ProductResponse.java services/catalog/src/main/java/com/microwave/catalog/product/ProductController.java services/catalog/src/test/java/com/microwave/catalog/product/ProductControllerTest.java
git commit -m "feat(catalog): add Product REST endpoints"
```

---

### Task 4: Catalog — standardized error handling

**Files:**
- Create: `services/catalog/src/main/java/com/microwave/catalog/error/ApiError.java`
- Create: `services/catalog/src/main/java/com/microwave/catalog/error/GlobalExceptionHandler.java`
- Create: `services/catalog/src/main/java/com/microwave/catalog/product/ProductNotFoundException.java`
- Modify: `services/catalog/src/main/java/com/microwave/catalog/product/ProductController.java`
- Modify: `services/catalog/src/test/java/com/microwave/catalog/product/ProductControllerTest.java`

**Interfaces:**
- Produces: `ApiError(Instant timestamp, int status, String error, String message, String path)`; `ProductNotFoundException(Long id)`

- [ ] **Step 1: Update the not-found test to assert the standardized error body**

Replace the `returnsNotFoundForMissingProduct` test in `ProductControllerTest.java` with:

```java
    @Test
    void returnsNotFoundForMissingProduct() throws Exception {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/products/99"));
    }
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductControllerTest#returnsNotFoundForMissingProduct`
Expected: FAIL — current 404 comes from a plain `ResponseStatusException`, no JSON body with `status`/`error`/`path` fields

- [ ] **Step 3: Create the ProductNotFoundException**

```java
package com.microwave.catalog.product;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long id) {
        super("Product not found: " + id);
    }
}
```

- [ ] **Step 4: Create the ApiError record**

```java
package com.microwave.catalog.error;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
```

- [ ] **Step 5: Create the global exception handler**

```java
package com.microwave.catalog.error;

import com.microwave.catalog.product.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(), HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
```

- [ ] **Step 6: Update the controller to throw ProductNotFoundException**

In `ProductController.java`, replace the `getProduct` method body's exception and remove the now-unused `ResponseStatusException`/`HttpStatus` import if no longer needed elsewhere (keep `HttpStatus` — it's still used by `@ResponseStatus(HttpStatus.CREATED)`):

```java
    @GetMapping("/{id}")
    public ProductResponse getProduct(@PathVariable Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        return ProductResponse.from(product);
    }
```

Remove the `import org.springframework.web.server.ResponseStatusException;` line from `ProductController.java`.

- [ ] **Step 7: Run the test and verify it passes**

Run: `mvn -f services/catalog/pom.xml test -Dtest=ProductControllerTest`
Expected: PASS

- [ ] **Step 8: Run the full catalog test suite**

Run: `mvn -f services/catalog/pom.xml test`
Expected: all tests PASS

- [ ] **Step 9: Commit**

```bash
git add services/catalog/src/main/java/com/microwave/catalog/error/ApiError.java services/catalog/src/main/java/com/microwave/catalog/error/GlobalExceptionHandler.java services/catalog/src/main/java/com/microwave/catalog/product/ProductNotFoundException.java services/catalog/src/main/java/com/microwave/catalog/product/ProductController.java services/catalog/src/test/java/com/microwave/catalog/product/ProductControllerTest.java
git commit -m "feat(catalog): standardize error responses"
```

---

## payments service

### Task 5: Payments service scaffold

**Files:**
- Create: `services/payments/pom.xml`
- Create: `services/payments/src/main/java/com/microwave/payments/PaymentsApplication.java`
- Create: `services/payments/src/main/resources/application.yml`
- Test: `services/payments/src/test/java/com/microwave/payments/PaymentsApplicationTests.java`

**Interfaces:**
- Produces: `com.microwave.payments.PaymentsApplication`, service listens on port 8082

- [ ] **Step 1: Create the Maven project file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>

    <groupId>com.microwave</groupId>
    <artifactId>payments</artifactId>
    <version>0.1.0</version>
    <name>payments</name>
    <description>Simulated payments service</description>

    <properties>
        <java.version>25</java.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the application config**

```yaml
server:
  port: 8082

spring:
  application:
    name: payments
```

- [ ] **Step 3: Write the failing context-load test**

```java
package com.microwave.payments;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PaymentsApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/payments/pom.xml test`
Expected: FAIL — no `@SpringBootConfiguration` found

- [ ] **Step 5: Create the application entry point**

```java
package com.microwave.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentsApplication.class, args);
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/payments/pom.xml test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/payments/pom.xml services/payments/src/main/java/com/microwave/payments/PaymentsApplication.java services/payments/src/main/resources/application.yml services/payments/src/test/java/com/microwave/payments/PaymentsApplicationTests.java
git commit -m "feat(payments): scaffold Spring Boot service"
```

---

### Task 6: Payments — Payment entity and repository

**Files:**
- Create: `services/payments/src/main/java/com/microwave/payments/payment/Payment.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentStatus.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentRepository.java`
- Modify: `services/payments/pom.xml`
- Modify: `services/payments/src/main/resources/application.yml`
- Test: `services/payments/src/test/java/com/microwave/payments/payment/PaymentRepositoryIT.java`

**Interfaces:**
- Produces: `PaymentStatus { APPROVED, REJECTED }`; `Payment(Long orderId, BigDecimal amount, PaymentStatus status)` with getters `getId()`, `getOrderId()`, `getAmount()`, `getStatus()`; `PaymentRepository extends JpaRepository<Payment, Long>` with `Optional<Payment> findByOrderId(Long orderId)`

- [ ] **Step 1: Add JPA, Postgres, and Testcontainers dependencies to the POM**

Add inside `<dependencyManagement>` (create the element if it doesn't exist yet):

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-bom</artifactId>
            <version>2.0.5</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add datasource and JPA config**

Append to `services/payments/src/main/resources/application.yml`:

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/payments_db
    username: payments
    password: payments
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

- [ ] **Step 3: Write the failing repository test**

```java
package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class PaymentRepositoryIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void savesAndFindsPaymentByOrderId() {
        paymentRepository.save(new Payment(42L, new BigDecimal("100.00"), PaymentStatus.APPROVED));

        Optional<Payment> found = paymentRepository.findByOrderId(42L);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(found.get().getAmount()).isEqualByComparingTo("100.00");
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentRepositoryIT`
Expected: FAIL — compile error, `Payment`, `PaymentStatus`, `PaymentRepository` don't exist yet

- [ ] **Step 5: Create the PaymentStatus enum**

```java
package com.microwave.payments.payment;

public enum PaymentStatus {
    APPROVED,
    REJECTED
}
```

- [ ] **Step 6: Create the Payment entity**

```java
package com.microwave.payments.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    protected Payment() {
    }

    public Payment(Long orderId, BigDecimal amount, PaymentStatus status) {
        this.orderId = orderId;
        this.amount = amount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }
}
```

- [ ] **Step 7: Create the repository**

```java
package com.microwave.payments.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);
}
```

- [ ] **Step 8: Run the test and verify it passes**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentRepositoryIT`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add services/payments/pom.xml services/payments/src/main/resources/application.yml services/payments/src/main/java/com/microwave/payments/payment/Payment.java services/payments/src/main/java/com/microwave/payments/payment/PaymentStatus.java services/payments/src/main/java/com/microwave/payments/payment/PaymentRepository.java services/payments/src/test/java/com/microwave/payments/payment/PaymentRepositoryIT.java
git commit -m "feat(payments): add Payment entity and repository"
```

---

### Task 7: Payments — simulation rule

**Files:**
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentSimulator.java`
- Test: `services/payments/src/test/java/com/microwave/payments/payment/PaymentSimulatorTest.java`

**Interfaces:**
- Consumes: `PaymentStatus` (Task 6)
- Produces: `PaymentSimulator.decide(BigDecimal amount): PaymentStatus`

- [ ] **Step 1: Write the failing unit test**

```java
package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentSimulatorTest {

    @Test
    void approvesAmountAtOrBelowLimit() {
        assertThat(PaymentSimulator.decide(new BigDecimal("10000"))).isEqualTo(PaymentStatus.APPROVED);
        assertThat(PaymentSimulator.decide(new BigDecimal("1")))
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void rejectsAmountAboveLimit() {
        assertThat(PaymentSimulator.decide(new BigDecimal("10000.01"))).isEqualTo(PaymentStatus.REJECTED);
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentSimulatorTest`
Expected: FAIL — compile error, `PaymentSimulator` doesn't exist yet

- [ ] **Step 3: Implement the simulator**

```java
package com.microwave.payments.payment;

import java.math.BigDecimal;

public final class PaymentSimulator {

    private static final BigDecimal APPROVAL_LIMIT = new BigDecimal("10000");

    private PaymentSimulator() {
    }

    public static PaymentStatus decide(BigDecimal amount) {
        return amount.compareTo(APPROVAL_LIMIT) <= 0 ? PaymentStatus.APPROVED : PaymentStatus.REJECTED;
    }
}
```

- [ ] **Step 4: Run the test and verify it passes**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentSimulatorTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/payment/PaymentSimulator.java services/payments/src/test/java/com/microwave/payments/payment/PaymentSimulatorTest.java
git commit -m "feat(payments): add payment simulation rule"
```

---

### Task 8: Payments — REST endpoints

**Files:**
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentRequest.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentResponse.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java`
- Test: `services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java`

**Interfaces:**
- Consumes: `Payment`, `PaymentStatus`, `PaymentRepository` (Task 6), `PaymentSimulator.decide` (Task 7)
- Produces: `POST /payments` (201), `GET /payments/{orderId}` (200 or 404 via `ResponseStatusException`)

- [ ] **Step 1: Write the failing controller test**

```java
package com.microwave.payments.payment;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @Test
    void approvesPaymentWithinLimit() throws Exception {
        Payment saved = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
        when(paymentRepository.save(any(Payment.class))).thenReturn(saved);

        mockMvc.perform(post("/payments")
                        .contentType("application/json")
                        .content("""
                                {"orderId":1,"amount":100.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void rejectsPaymentAboveLimit() throws Exception {
        Payment saved = new Payment(2L, new BigDecimal("15000.00"), PaymentStatus.REJECTED);
        when(paymentRepository.save(any(Payment.class))).thenReturn(saved);

        mockMvc.perform(post("/payments")
                        .contentType("application/json")
                        .content("""
                                {"orderId":2,"amount":15000.00}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void getsPaymentByOrderId() throws Exception {
        Payment payment = new Payment(1L, new BigDecimal("100.00"), PaymentStatus.APPROVED);
        when(paymentRepository.findByOrderId(1L)).thenReturn(Optional.of(payment));

        mockMvc.perform(get("/payments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void returnsNotFoundForMissingPayment() throws Exception {
        when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/payments/99"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentControllerTest`
Expected: FAIL — compile error, `PaymentController`, `PaymentRequest`, `PaymentResponse` don't exist yet

- [ ] **Step 3: Create the request DTO**

```java
package com.microwave.payments.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotNull Long orderId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount) {
}
```

- [ ] **Step 4: Create the response DTO**

```java
package com.microwave.payments.payment;

import java.math.BigDecimal;

public record PaymentResponse(Long id, Long orderId, BigDecimal amount, PaymentStatus status) {

    static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getAmount(), payment.getStatus());
    }
}
```

- [ ] **Step 5: Create the controller**

```java
package com.microwave.payments.payment;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse charge(@Valid @RequestBody PaymentRequest request) {
        PaymentStatus status = PaymentSimulator.decide(request.amount());
        Payment payment = paymentRepository.save(new Payment(request.orderId(), request.amount(), status));
        return PaymentResponse.from(payment);
    }

    @GetMapping("/{orderId}")
    public PaymentResponse getByOrderId(@PathVariable Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Payment not found for order: " + orderId));
        return PaymentResponse.from(payment);
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentControllerTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/payment/PaymentRequest.java services/payments/src/main/java/com/microwave/payments/payment/PaymentResponse.java services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java
git commit -m "feat(payments): add Payment REST endpoints"
```

---

### Task 9: Payments — standardized error handling

**Files:**
- Create: `services/payments/src/main/java/com/microwave/payments/error/ApiError.java`
- Create: `services/payments/src/main/java/com/microwave/payments/error/GlobalExceptionHandler.java`
- Create: `services/payments/src/main/java/com/microwave/payments/payment/PaymentNotFoundException.java`
- Modify: `services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java`
- Modify: `services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java`

**Interfaces:**
- Produces: `ApiError(Instant timestamp, int status, String error, String message, String path)`; `PaymentNotFoundException(Long orderId)`

- [ ] **Step 1: Update the not-found test to assert the standardized error body**

Replace the `returnsNotFoundForMissingPayment` test in `PaymentControllerTest.java` with:

```java
    @Test
    void returnsNotFoundForMissingPayment() throws Exception {
        when(paymentRepository.findByOrderId(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/payments/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/payments/99"));
    }
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentControllerTest#returnsNotFoundForMissingPayment`
Expected: FAIL — current 404 has no `status`/`error`/`path` body fields

- [ ] **Step 3: Create the PaymentNotFoundException**

```java
package com.microwave.payments.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long orderId) {
        super("Payment not found for order: " + orderId);
    }
}
```

- [ ] **Step 4: Create the ApiError record**

```java
package com.microwave.payments.error;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
```

- [ ] **Step 5: Create the global exception handler**

```java
package com.microwave.payments.error;

import com.microwave.payments.payment.PaymentNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ApiError> handlePaymentNotFound(PaymentNotFoundException ex, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(), HttpStatus.NOT_FOUND.value(), "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }
}
```

- [ ] **Step 6: Update the controller to throw PaymentNotFoundException**

In `PaymentController.java`, replace the `getByOrderId` method body and remove the now-unused `ResponseStatusException` import:

```java
    @GetMapping("/{orderId}")
    public PaymentResponse getByOrderId(@PathVariable Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException(orderId));
        return PaymentResponse.from(payment);
    }
```

Remove the `import org.springframework.web.server.ResponseStatusException;` line from `PaymentController.java`.

- [ ] **Step 7: Run the test and verify it passes**

Run: `mvn -f services/payments/pom.xml test -Dtest=PaymentControllerTest`
Expected: PASS

- [ ] **Step 8: Run the full payments test suite**

Run: `mvn -f services/payments/pom.xml test`
Expected: all tests PASS

- [ ] **Step 9: Commit**

```bash
git add services/payments/src/main/java/com/microwave/payments/error/ApiError.java services/payments/src/main/java/com/microwave/payments/error/GlobalExceptionHandler.java services/payments/src/main/java/com/microwave/payments/payment/PaymentNotFoundException.java services/payments/src/main/java/com/microwave/payments/payment/PaymentController.java services/payments/src/test/java/com/microwave/payments/payment/PaymentControllerTest.java
git commit -m "feat(payments): standardize error responses"
```

---

## orders service

### Task 10: Orders service scaffold

**Files:**
- Create: `services/orders/pom.xml`
- Create: `services/orders/src/main/java/com/microwave/orders/OrdersApplication.java`
- Create: `services/orders/src/main/resources/application.yml`
- Test: `services/orders/src/test/java/com/microwave/orders/OrdersApplicationTests.java`

**Interfaces:**
- Produces: `com.microwave.orders.OrdersApplication` (annotated `@EnableFeignClients`), service listens on port 8083

- [ ] **Step 1: Create the Maven project file**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>

    <groupId>com.microwave</groupId>
    <artifactId>orders</artifactId>
    <version>0.1.0</version>
    <name>orders</name>
    <description>Order orchestration service</description>

    <properties>
        <java.version>25</java.version>
        <spring-cloud.version>2025.1.2</spring-cloud.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>2.0.5</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Create the application config**

```yaml
server:
  port: 8083

spring:
  application:
    name: orders

catalog:
  service:
    url: http://localhost:8081

payments:
  service:
    url: http://localhost:8082
```

- [ ] **Step 3: Write the failing context-load test**

```java
package com.microwave.orders;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class OrdersApplicationTests {

    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test`
Expected: FAIL — no `@SpringBootConfiguration` found

- [ ] **Step 5: Create the application entry point**

```java
package com.microwave.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class OrdersApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersApplication.class, args);
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/orders/pom.xml test`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/orders/pom.xml services/orders/src/main/java/com/microwave/orders/OrdersApplication.java services/orders/src/main/resources/application.yml services/orders/src/test/java/com/microwave/orders/OrdersApplicationTests.java
git commit -m "feat(orders): scaffold Spring Boot service with OpenFeign"
```

---

### Task 11: Orders — Order entity and repository

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/order/Order.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderStatus.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderRepository.java`
- Modify: `services/orders/pom.xml`
- Modify: `services/orders/src/main/resources/application.yml`
- Test: `services/orders/src/test/java/com/microwave/orders/order/OrderRepositoryIT.java`

**Interfaces:**
- Produces: `OrderStatus { CREATED, CONFIRMED, REJECTED }`; `Order(Long productId, int quantity, BigDecimal totalAmount, OrderStatus status)` with getters `getId()`, `getProductId()`, `getQuantity()`, `getTotalAmount()`, `getStatus()`, and `updateStatus(OrderStatus status)`; `OrderRepository extends JpaRepository<Order, Long>`

- [ ] **Step 1: Add JPA, Postgres, and Testcontainers dependencies to the POM**

Add inside `<dependencies>` (the `testcontainers-bom` import already exists in `<dependencyManagement>` from Task 10):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Add datasource and JPA config**

Append to `services/orders/src/main/resources/application.yml`:

```yaml
  datasource:
    url: jdbc:postgresql://localhost:5432/orders_db
    username: orders
    password: orders
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
```

- [ ] **Step 3: Write the failing repository test**

```java
package com.microwave.orders.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class OrderRepositoryIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void savesAndFindsOrder() {
        Order saved = orderRepository.save(
                new Order(1L, 2, new BigDecimal("700.00"), OrderStatus.CREATED));

        Optional<Order> found = orderRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(found.get().getTotalAmount()).isEqualByComparingTo("700.00");
    }

    @Test
    void updatesOrderStatus() {
        Order saved = orderRepository.save(
                new Order(1L, 2, new BigDecimal("700.00"), OrderStatus.CREATED));

        saved.updateStatus(OrderStatus.CONFIRMED);
        orderRepository.save(saved);

        Optional<Order> found = orderRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderRepositoryIT`
Expected: FAIL — compile error, `Order`, `OrderStatus`, `OrderRepository` don't exist yet

- [ ] **Step 5: Create the OrderStatus enum**

```java
package com.microwave.orders.order;

public enum OrderStatus {
    CREATED,
    CONFIRMED,
    REJECTED
}
```

- [ ] **Step 6: Create the Order entity**

```java
package com.microwave.orders.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    protected Order() {
    }

    public Order(Long productId, int quantity, BigDecimal totalAmount, OrderStatus status) {
        this.productId = productId;
        this.quantity = quantity;
        this.totalAmount = totalAmount;
        this.status = status;
    }

    public void updateStatus(OrderStatus status) {
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public OrderStatus getStatus() {
        return status;
    }
}
```

- [ ] **Step 7: Create the repository**

```java
package com.microwave.orders.order;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {
}
```

- [ ] **Step 8: Run the test and verify it passes**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderRepositoryIT`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add services/orders/pom.xml services/orders/src/main/resources/application.yml services/orders/src/main/java/com/microwave/orders/order/Order.java services/orders/src/main/java/com/microwave/orders/order/OrderStatus.java services/orders/src/main/java/com/microwave/orders/order/OrderRepository.java services/orders/src/test/java/com/microwave/orders/order/OrderRepositoryIT.java
git commit -m "feat(orders): add Order entity and repository"
```

---

### Task 12: Orders — Feign clients for catalog and payments

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/catalog/ProductDto.java`
- Create: `services/orders/src/main/java/com/microwave/orders/catalog/CatalogClient.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentStatusDto.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentRequestDto.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentResponseDto.java`
- Create: `services/orders/src/main/java/com/microwave/orders/payments/PaymentsClient.java`
- Modify: `services/orders/pom.xml`
- Test: `services/orders/src/test/java/com/microwave/orders/catalog/CatalogClientIT.java`
- Test: `services/orders/src/test/java/com/microwave/orders/payments/PaymentsClientIT.java`

**Interfaces:**
- Produces: `ProductDto(Long id, String name, String description, BigDecimal price)`; `CatalogClient.getProduct(Long id): ProductDto`; `PaymentStatusDto { APPROVED, REJECTED }`; `PaymentRequestDto(Long orderId, BigDecimal amount)`; `PaymentResponseDto(Long id, Long orderId, BigDecimal amount, PaymentStatusDto status)`; `PaymentsClient.charge(PaymentRequestDto request): PaymentResponseDto`

- [ ] **Step 1: Add the WireMock test dependency to the POM**

Add inside `<dependencies>`:

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.13.2</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Write the failing CatalogClient test**

```java
package com.microwave.orders.catalog;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class CatalogClientIT {

    static final WireMockServer wireMockServer = new WireMockServer(0);

    @DynamicPropertySource
    static void configureCatalogUrl(DynamicPropertyRegistry registry) {
        wireMockServer.start();
        registry.add("catalog.service.url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private CatalogClient catalogClient;

    @AfterEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    void fetchesProductFromCatalogService() {
        wireMockServer.stubFor(get(urlEqualTo("/products/1"))
                .willReturn(okJson("""
                        {"id":1,"name":"Keyboard","description":"Mechanical keyboard","price":350.00}
                        """)));

        ProductDto product = catalogClient.getProduct(1L);

        assertThat(product.name()).isEqualTo("Keyboard");
        assertThat(product.price()).isEqualByComparingTo("350.00");
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test -Dtest=CatalogClientIT`
Expected: FAIL — compile error, `ProductDto`, `CatalogClient` don't exist yet

- [ ] **Step 4: Create the ProductDto**

```java
package com.microwave.orders.catalog;

import java.math.BigDecimal;

public record ProductDto(Long id, String name, String description, BigDecimal price) {
}
```

- [ ] **Step 5: Create the CatalogClient**

```java
package com.microwave.orders.catalog;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@FeignClient(name = "catalog", url = "${catalog.service.url}")
public interface CatalogClient {

    @GetMapping("/products/{id}")
    ProductDto getProduct(@PathVariable("id") Long id);
}
```

- [ ] **Step 6: Run the CatalogClient test and verify it passes**

Run: `mvn -f services/orders/pom.xml test -Dtest=CatalogClientIT`
Expected: PASS

- [ ] **Step 7: Write the failing PaymentsClient test**

```java
package com.microwave.orders.payments;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
class PaymentsClientIT {

    static final WireMockServer wireMockServer = new WireMockServer(0);

    @DynamicPropertySource
    static void configurePaymentsUrl(DynamicPropertyRegistry registry) {
        wireMockServer.start();
        registry.add("payments.service.url", () -> "http://localhost:" + wireMockServer.port());
    }

    @Autowired
    private PaymentsClient paymentsClient;

    @AfterEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    void chargesPaymentThroughPaymentsService() {
        wireMockServer.stubFor(post(urlEqualTo("/payments"))
                .willReturn(okJson("""
                        {"id":1,"orderId":42,"amount":100.00,"status":"APPROVED"}
                        """)));

        PaymentResponseDto response = paymentsClient.charge(new PaymentRequestDto(42L, new BigDecimal("100.00")));

        assertThat(response.status()).isEqualTo(PaymentStatusDto.APPROVED);
        assertThat(response.amount()).isEqualByComparingTo("100.00");
    }
}
```

- [ ] **Step 8: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test -Dtest=PaymentsClientIT`
Expected: FAIL — compile error, `PaymentStatusDto`, `PaymentRequestDto`, `PaymentResponseDto`, `PaymentsClient` don't exist yet

- [ ] **Step 9: Create the PaymentStatusDto enum**

```java
package com.microwave.orders.payments;

public enum PaymentStatusDto {
    APPROVED,
    REJECTED
}
```

- [ ] **Step 10: Create the PaymentRequestDto and PaymentResponseDto**

```java
package com.microwave.orders.payments;

import java.math.BigDecimal;

public record PaymentRequestDto(Long orderId, BigDecimal amount) {
}
```

```java
package com.microwave.orders.payments;

import java.math.BigDecimal;

public record PaymentResponseDto(Long id, Long orderId, BigDecimal amount, PaymentStatusDto status) {
}
```

- [ ] **Step 11: Create the PaymentsClient**

```java
package com.microwave.orders.payments;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(name = "payments", url = "${payments.service.url}")
public interface PaymentsClient {

    @PostMapping("/payments")
    PaymentResponseDto charge(@RequestBody PaymentRequestDto request);
}
```

- [ ] **Step 12: Run the PaymentsClient test and verify it passes**

Run: `mvn -f services/orders/pom.xml test -Dtest=PaymentsClientIT`
Expected: PASS

- [ ] **Step 13: Commit**

```bash
git add services/orders/pom.xml services/orders/src/main/java/com/microwave/orders/catalog services/orders/src/main/java/com/microwave/orders/payments services/orders/src/test/java/com/microwave/orders/catalog/CatalogClientIT.java services/orders/src/test/java/com/microwave/orders/payments/PaymentsClientIT.java
git commit -m "feat(orders): add Feign clients for catalog and payments"
```

---

### Task 13: Orders — order creation orchestration

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/order/ProductNotFoundException.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/UpstreamServiceUnavailableException.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderService.java`
- Test: `services/orders/src/test/java/com/microwave/orders/order/OrderServiceTest.java`

**Interfaces:**
- Consumes: `Order`, `OrderStatus`, `OrderRepository` (Task 11); `CatalogClient`, `ProductDto` (Task 12); `PaymentsClient`, `PaymentRequestDto`, `PaymentResponseDto`, `PaymentStatusDto` (Task 12)
- Produces: `OrderService.createOrder(Long productId, int quantity): Order`; `ProductNotFoundException(Long productId)`; `UpstreamServiceUnavailableException(String serviceName, Throwable cause)`

- [ ] **Step 1: Write the failing orchestration tests**

```java
package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductDto;
import com.microwave.orders.payments.PaymentRequestDto;
import com.microwave.orders.payments.PaymentResponseDto;
import com.microwave.orders.payments.PaymentStatusDto;
import com.microwave.orders.payments.PaymentsClient;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private CatalogClient catalogClient;

    @Mock
    private PaymentsClient paymentsClient;

    private OrderService orderService;

    private static FeignException feignErrorWithStatus(int status) {
        Request request = Request.create(
                Request.HttpMethod.GET, "/products/1", Map.of(), null, StandardCharsets.UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .request(request)
                .headers(Map.of())
                .build();
        return FeignException.errorStatus("Client#method", response);
    }

    private void initService() {
        orderService = new OrderService(orderRepository, catalogClient, paymentsClient);
    }

    @Test
    void createsAndConfirmsOrderOnApprovedPayment() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class)))
                .thenReturn(new PaymentResponseDto(1L, 100L, new BigDecimal("200.00"), PaymentStatusDto.APPROVED));

        Order order = orderService.createOrder(1L, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(order.getTotalAmount()).isEqualByComparingTo("200.00");
        verify(orderRepository, times(2)).save(any(Order.class));
    }

    @Test
    void rejectsOrderOnRejectedPayment() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class)))
                .thenReturn(new PaymentResponseDto(1L, 100L, new BigDecimal("200.00"), PaymentStatusDto.REJECTED));

        Order order = orderService.createOrder(1L, 2);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REJECTED);
    }

    @Test
    void throwsProductNotFoundAndCreatesNoOrder() {
        initService();
        when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(404));

        assertThatThrownBy(() -> orderService.createOrder(1L, 2))
                .isInstanceOf(ProductNotFoundException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void throwsUpstreamUnavailableWhenCatalogFails() {
        initService();
        when(catalogClient.getProduct(1L)).thenThrow(feignErrorWithStatus(500));

        assertThatThrownBy(() -> orderService.createOrder(1L, 2))
                .isInstanceOf(UpstreamServiceUnavailableException.class);
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void keepsOrderCreatedWhenPaymentsUnavailable() {
        initService();
        when(catalogClient.getProduct(1L))
                .thenReturn(new ProductDto(1L, "Keyboard", "Mechanical keyboard", new BigDecimal("100.00")));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentsClient.charge(any(PaymentRequestDto.class))).thenThrow(feignErrorWithStatus(503));

        assertThatThrownBy(() -> orderService.createOrder(1L, 2))
                .isInstanceOf(UpstreamServiceUnavailableException.class);

        // The order was already persisted as CREATED before the payments call failed,
        // and it is NOT rolled back — see docs/tech-debt.md TD-1.
        verify(orderRepository, times(1)).save(any(Order.class));
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderServiceTest`
Expected: FAIL — compile error, `OrderService`, `ProductNotFoundException`, `UpstreamServiceUnavailableException` don't exist yet

- [ ] **Step 3: Create the ProductNotFoundException**

```java
package com.microwave.orders.order;

public class ProductNotFoundException extends RuntimeException {

    public ProductNotFoundException(Long productId) {
        super("Product not found: " + productId);
    }
}
```

- [ ] **Step 4: Create the UpstreamServiceUnavailableException**

```java
package com.microwave.orders.order;

public class UpstreamServiceUnavailableException extends RuntimeException {

    public UpstreamServiceUnavailableException(String serviceName, Throwable cause) {
        super(serviceName + " service is unavailable", cause);
    }
}
```

- [ ] **Step 5: Create the OrderService**

```java
package com.microwave.orders.order;

import com.microwave.orders.catalog.CatalogClient;
import com.microwave.orders.catalog.ProductDto;
import com.microwave.orders.payments.PaymentRequestDto;
import com.microwave.orders.payments.PaymentResponseDto;
import com.microwave.orders.payments.PaymentStatusDto;
import com.microwave.orders.payments.PaymentsClient;
import feign.FeignException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final PaymentsClient paymentsClient;

    public OrderService(OrderRepository orderRepository, CatalogClient catalogClient, PaymentsClient paymentsClient) {
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.paymentsClient = paymentsClient;
    }

    // Intentionally NOT @Transactional: the order created below must survive
    // even if the payments call that follows fails. See docs/tech-debt.md TD-1 —
    // wrapping this in a single transaction would roll back the CREATED order
    // whenever payments is unreachable, which is explicitly not what Phase 1 does.
    public Order createOrder(Long productId, int quantity) {
        ProductDto product = fetchProduct(productId);
        BigDecimal totalAmount = product.price().multiply(BigDecimal.valueOf(quantity));

        Order order = orderRepository.save(new Order(productId, quantity, totalAmount, OrderStatus.CREATED));

        PaymentResponseDto payment = requestPayment(order);

        order.updateStatus(payment.status() == PaymentStatusDto.APPROVED ? OrderStatus.CONFIRMED : OrderStatus.REJECTED);
        return orderRepository.save(order);
    }

    private ProductDto fetchProduct(Long productId) {
        try {
            return catalogClient.getProduct(productId);
        } catch (FeignException ex) {
            if (ex.status() == 404) {
                throw new ProductNotFoundException(productId);
            }
            throw new UpstreamServiceUnavailableException("catalog", ex);
        }
    }

    private PaymentResponseDto requestPayment(Order order) {
        try {
            return paymentsClient.charge(new PaymentRequestDto(order.getId(), order.getTotalAmount()));
        } catch (FeignException ex) {
            throw new UpstreamServiceUnavailableException("payments", ex);
        }
    }
}
```

- [ ] **Step 6: Run the test and verify it passes**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/order/ProductNotFoundException.java services/orders/src/main/java/com/microwave/orders/order/UpstreamServiceUnavailableException.java services/orders/src/main/java/com/microwave/orders/order/OrderService.java services/orders/src/test/java/com/microwave/orders/order/OrderServiceTest.java
git commit -m "feat(orders): add order creation orchestration"
```

---

### Task 14: Orders — REST endpoints

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderRequest.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderResponse.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderNotFoundException.java`
- Create: `services/orders/src/main/java/com/microwave/orders/order/OrderController.java`
- Modify: `services/orders/src/main/java/com/microwave/orders/order/OrderService.java`
- Test: `services/orders/src/test/java/com/microwave/orders/order/OrderControllerTest.java`

**Interfaces:**
- Consumes: `Order`, `OrderStatus`, `OrderService.createOrder` (Task 13)
- Produces: `OrderService.findById(Long id): Order`, `OrderService.findAll(): List<Order>`; `POST /orders` (201), `GET /orders/{id}` (200 or 404), `GET /orders` (200); `OrderNotFoundException(Long id)`

- [ ] **Step 1: Add read methods to OrderService**

Add to `services/orders/src/main/java/com/microwave/orders/order/OrderService.java`, and add `import java.util.List;` at the top:

```java
    public Order findById(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> findAll() {
        return orderRepository.findAll();
    }
```

- [ ] **Step 2: Create the OrderNotFoundException**

```java
package com.microwave.orders.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long id) {
        super("Order not found: " + id);
    }
}
```

- [ ] **Step 3: Write the failing controller test**

```java
package com.microwave.orders.order;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @Test
    void createsOrder() throws Exception {
        Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
        when(orderService.createOrder(1L, 2)).thenReturn(order);

        mockMvc.perform(post("/orders")
                        .contentType("application/json")
                        .content("""
                                {"productId":1,"quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void rejectsOrderWithZeroQuantity() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType("application/json")
                        .content("""
                                {"productId":1,"quantity":0}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getsOrderById() throws Exception {
        Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
        when(orderService.findById(1L)).thenReturn(order);

        mockMvc.perform(get("/orders/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void listsOrders() throws Exception {
        Order order = new Order(1L, 2, new BigDecimal("200.00"), OrderStatus.CONFIRMED);
        when(orderService.findAll()).thenReturn(List.of(order));

        mockMvc.perform(get("/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderControllerTest`
Expected: FAIL — compile error, `OrderController`, `OrderRequest`, `OrderResponse` don't exist yet

- [ ] **Step 5: Create the request DTO**

```java
package com.microwave.orders.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record OrderRequest(
        @NotNull Long productId,
        @Min(1) int quantity) {
}
```

- [ ] **Step 6: Create the response DTO**

```java
package com.microwave.orders.order;

import java.math.BigDecimal;

public record OrderResponse(Long id, Long productId, int quantity, BigDecimal totalAmount, OrderStatus status) {

    static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
    }
}
```

- [ ] **Step 7: Create the controller**

```java
package com.microwave.orders.order;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse createOrder(@Valid @RequestBody OrderRequest request) {
        Order order = orderService.createOrder(request.productId(), request.quantity());
        return OrderResponse.from(order);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@PathVariable Long id) {
        return OrderResponse.from(orderService.findById(id));
    }

    @GetMapping
    public List<OrderResponse> listOrders() {
        return orderService.findAll().stream()
                .map(OrderResponse::from)
                .toList();
    }
}
```

- [ ] **Step 8: Run the test and verify it passes**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderControllerTest`
Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/order/OrderRequest.java services/orders/src/main/java/com/microwave/orders/order/OrderResponse.java services/orders/src/main/java/com/microwave/orders/order/OrderNotFoundException.java services/orders/src/main/java/com/microwave/orders/order/OrderController.java services/orders/src/main/java/com/microwave/orders/order/OrderService.java services/orders/src/test/java/com/microwave/orders/order/OrderControllerTest.java
git commit -m "feat(orders): add Order REST endpoints"
```

---

### Task 15: Orders — standardized error handling

**Files:**
- Create: `services/orders/src/main/java/com/microwave/orders/error/ApiError.java`
- Create: `services/orders/src/main/java/com/microwave/orders/error/GlobalExceptionHandler.java`
- Modify: `services/orders/src/test/java/com/microwave/orders/order/OrderControllerTest.java`

**Interfaces:**
- Consumes: `OrderNotFoundException`, `ProductNotFoundException`, `UpstreamServiceUnavailableException` (Tasks 13–14)
- Produces: `ApiError(Instant timestamp, int status, String error, String message, String path)`

- [ ] **Step 1: Add failing error-mapping tests to OrderControllerTest**

Add these imports to `OrderControllerTest.java`:

```java
import static org.mockito.Mockito.doThrow;
```

Add these test methods:

```java
    @Test
    void returnsNotFoundForMissingOrder() throws Exception {
        when(orderService.findById(99L)).thenThrow(new OrderNotFoundException(99L));

        mockMvc.perform(get("/orders/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/orders/99"));
    }

    @Test
    void returnsNotFoundWhenProductMissing() throws Exception {
        doThrow(new ProductNotFoundException(1L)).when(orderService).createOrder(1L, 2);

        mockMvc.perform(post("/orders")
                        .contentType("application/json")
                        .content("""
                                {"productId":1,"quantity":2}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnsServiceUnavailableWhenUpstreamFails() throws Exception {
        doThrow(new UpstreamServiceUnavailableException("payments", new RuntimeException("boom")))
                .when(orderService).createOrder(1L, 2);

        mockMvc.perform(post("/orders")
                        .contentType("application/json")
                        .content("""
                                {"productId":1,"quantity":2}
                                """))
                .andExpect(status().isServiceUnavailable());
    }
```

- [ ] **Step 2: Run the tests and verify they fail**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderControllerTest`
Expected: FAIL — `OrderNotFoundException` propagates as an unhandled 500, not mapped to 404/503 yet

- [ ] **Step 3: Create the ApiError record**

```java
package com.microwave.orders.error;

import java.time.Instant;

public record ApiError(Instant timestamp, int status, String error, String message, String path) {
}
```

- [ ] **Step 4: Create the global exception handler**

```java
package com.microwave.orders.error;

import com.microwave.orders.order.OrderNotFoundException;
import com.microwave.orders.order.ProductNotFoundException;
import com.microwave.orders.order.UpstreamServiceUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ApiError> handleOrderNotFound(OrderNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiError> handleProductNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(UpstreamServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleUpstreamUnavailable(
            UpstreamServiceUnavailableException ex, HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
```

- [ ] **Step 5: Run the tests and verify they pass**

Run: `mvn -f services/orders/pom.xml test -Dtest=OrderControllerTest`
Expected: PASS

- [ ] **Step 6: Run the full orders test suite**

Run: `mvn -f services/orders/pom.xml test`
Expected: all tests PASS

- [ ] **Step 7: Commit**

```bash
git add services/orders/src/main/java/com/microwave/orders/error/ApiError.java services/orders/src/main/java/com/microwave/orders/error/GlobalExceptionHandler.java services/orders/src/test/java/com/microwave/orders/order/OrderControllerTest.java
git commit -m "feat(orders): standardize error responses"
```

---

## Wrap-up

### Task 16: Root README

**Files:**
- Create: `README.md`

**Interfaces:**
- (none — documentation only)

- [ ] **Step 1: Write the README**

```markdown
# Microwave

A learning project: an e-commerce system built incrementally to practice microservices in Java, messaging with RabbitMQ/Kafka, containerization with Docker, and orchestration with Kubernetes.

See [docs/roadmap.md](docs/roadmap.md) for the full roadmap and [docs/tech-debt.md](docs/tech-debt.md) for known limitations.

## Phase 1 — Microservices foundation

Three independent Spring Boot services, each with its own PostgreSQL database, communicating over synchronous REST:

- `services/catalog` (port 8081) — product catalog
- `services/payments` (port 8082) — simulated payment processing
- `services/orders` (port 8083) — order orchestration (calls `catalog` and `payments` via OpenFeign)

### Requirements

- Java 25
- Maven
- Docker (required by Testcontainers for integration tests)

### Running the tests

Each service is an independent Maven module:

\`\`\`bash
mvn -f services/catalog/pom.xml test
mvn -f services/payments/pom.xml test
mvn -f services/orders/pom.xml test
\`\`\`

### Running a service locally

Each service expects a local PostgreSQL database matching its `application.yml` datasource config (see each service's `src/main/resources/application.yml`). Docker Compose support for running all services and databases together is planned for Phase 2.

\`\`\`bash
mvn -f services/catalog/pom.xml spring-boot:run
\`\`\`
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: add root README"
```
