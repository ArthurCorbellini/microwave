# Phase 2 Containerization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Package `catalog`, `orders`, and `payments` as Docker images and orchestrate them plus their databases via `docker-compose.yml`, so the full Phase 1 flow works end-to-end with just `docker-compose up`.

**Architecture:** Each service gets a multi-stage `Dockerfile` (Maven build stage → JRE runtime stage) and a Spring Actuator `/actuator/health` endpoint. A root-level `docker-compose.yml` wires 6 containers (3 services + 3 Postgres instances) together via service-name networking, env-var configuration, healthcheck-gated startup ordering, and named volumes.

**Tech Stack:** Docker (via `docker-compose` CLI pointed at the Podman socket, per `mise.local.toml`), `maven:3.9.16-eclipse-temurin-25` (build stage), `eclipse-temurin:25-jre` (runtime stage), `postgres:17-alpine`, Spring Boot Actuator.

## Global Constraints

- Java 25 / Spring Boot 4.0.7 — matches `mise.toml` and each service's `pom.xml`; do not change these.
- No changes to any `application.yml`'s existing `localhost` defaults — configuration for containers comes entirely from `docker-compose.yml` environment variables (relaxed binding), per the approved spec.
- Only `/actuator/health` is exposed via `management.endpoints.web.exposure.include=health` — never expose the full actuator surface, since app ports are published to the host.
- Postgres image tag is `postgres:17-alpine` everywhere (matches the version already used by Testcontainers in existing `*IT.java` tests).
- Follow existing test conventions: `@SpringBootTest` + `@Testcontainers` + `@Container @ServiceConnection static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");` for integration tests (see `services/catalog/src/test/java/com/microwave/catalog/CatalogApplicationTests.java`), file suffix `IT.java` so `maven-failsafe-plugin` picks it up.
- Design spec: `docs/superpowers/specs/2026-08-11-phase2-containerization-design.md` — refer back to it for the full rationale behind each decision below.

---

### Task 1: `catalog` — Actuator health endpoint + Dockerfile

**Files:**
- Modify: `services/catalog/pom.xml`
- Modify: `services/catalog/src/main/resources/application.yml`
- Create: `services/catalog/src/test/java/com/microwave/catalog/ActuatorHealthIT.java`
- Create: `services/catalog/Dockerfile`
- Create: `services/catalog/.dockerignore`

**Interfaces:**
- Produces: `catalog` container listens on port `8081`, image buildable via `docker build services/catalog`, exposes `GET /actuator/health` returning `{"status":"UP"}` when its datasource is reachable.

- [ ] **Step 1: Write the failing test**

Create `services/catalog/src/test/java/com/microwave/catalog/ActuatorHealthIT.java`:

```java
package com.microwave.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActuatorHealthIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private MockMvc mockMvc;

  @Test
  void healthEndpointReportsUp() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f services/catalog/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: FAIL — `/actuator/health` doesn't exist yet, so the request returns `404` instead of `200`.

- [ ] **Step 3: Add the Actuator dependency**

In `services/catalog/pom.xml`, inside the `<dependencies>` block, add (next to the other `spring-boot-starter-*` entries):

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 4: Expose only the health endpoint**

Append to `services/catalog/src/main/resources/application.yml` (top-level key, same indentation level as `server:` and `spring:`):

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f services/catalog/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Write the Dockerfile**

Create `services/catalog/Dockerfile`:

```dockerfile
# Stage 1: build
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 7: Write `.dockerignore`**

Create `services/catalog/.dockerignore`:

```
target/
```

- [ ] **Step 8: Verify the image builds**

Run: `docker build -t catalog:local services/catalog`
Expected: build completes successfully (final line similar to `=> naming to docker.io/library/catalog:local` or `Successfully tagged catalog:local`). This only proves the image builds — the container isn't runnable standalone yet since it needs a reachable Postgres, which Task 4's `docker-compose.yml` provides.

- [ ] **Step 9: Commit**

```bash
git add services/catalog/pom.xml services/catalog/src/main/resources/application.yml services/catalog/src/test/java/com/microwave/catalog/ActuatorHealthIT.java services/catalog/Dockerfile services/catalog/.dockerignore
git commit -m "feat(catalog): add actuator health endpoint and Dockerfile"
```

---

### Task 2: `orders` — Actuator health endpoint + Dockerfile

**Files:**
- Modify: `services/orders/pom.xml`
- Modify: `services/orders/src/main/resources/application.yml`
- Create: `services/orders/src/test/java/com/microwave/orders/ActuatorHealthIT.java`
- Create: `services/orders/Dockerfile`
- Create: `services/orders/.dockerignore`

**Interfaces:**
- Produces: `orders` container listens on port `8083`, image buildable via `docker build services/orders`, exposes `GET /actuator/health` returning `{"status":"UP"}` when its datasource is reachable.

- [ ] **Step 1: Write the failing test**

Create `services/orders/src/test/java/com/microwave/orders/ActuatorHealthIT.java`:

```java
package com.microwave.orders;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActuatorHealthIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private MockMvc mockMvc;

  @Test
  void healthEndpointReportsUp() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f services/orders/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: FAIL — `404` instead of `200`.

- [ ] **Step 3: Add the Actuator dependency**

In `services/orders/pom.xml`, inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 4: Expose only the health endpoint**

Append to `services/orders/src/main/resources/application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f services/orders/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Write the Dockerfile**

Create `services/orders/Dockerfile`:

```dockerfile
# Stage 1: build
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 7: Write `.dockerignore`**

Create `services/orders/.dockerignore`:

```
target/
```

- [ ] **Step 8: Verify the image builds**

Run: `docker build -t orders:local services/orders`
Expected: build completes successfully.

- [ ] **Step 9: Commit**

```bash
git add services/orders/pom.xml services/orders/src/main/resources/application.yml services/orders/src/test/java/com/microwave/orders/ActuatorHealthIT.java services/orders/Dockerfile services/orders/.dockerignore
git commit -m "feat(orders): add actuator health endpoint and Dockerfile"
```

---

### Task 3: `payments` — Actuator health endpoint + Dockerfile

**Files:**
- Modify: `services/payments/pom.xml`
- Modify: `services/payments/src/main/resources/application.yml`
- Create: `services/payments/src/test/java/com/microwave/payments/ActuatorHealthIT.java`
- Create: `services/payments/Dockerfile`
- Create: `services/payments/.dockerignore`

**Interfaces:**
- Produces: `payments` container listens on port `8082`, image buildable via `docker build services/payments`, exposes `GET /actuator/health` returning `{"status":"UP"}` when its datasource is reachable.

- [ ] **Step 1: Write the failing test**

Create `services/payments/src/test/java/com/microwave/payments/ActuatorHealthIT.java`:

```java
package com.microwave.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ActuatorHealthIT {

  @Container
  @ServiceConnection
  static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired
  private MockMvc mockMvc;

  @Test
  void healthEndpointReportsUp() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -f services/payments/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: FAIL — `404` instead of `200`.

- [ ] **Step 3: Add the Actuator dependency**

In `services/payments/pom.xml`, inside `<dependencies>`, add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

- [ ] **Step 4: Expose only the health endpoint**

Append to `services/payments/src/main/resources/application.yml`:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `mvn -f services/payments/pom.xml verify -Dit.test=ActuatorHealthIT -DfailIfNoTests=false`
Expected: PASS

- [ ] **Step 6: Write the Dockerfile**

Create `services/payments/Dockerfile`:

```dockerfile
# Stage 1: build
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 7: Write `.dockerignore`**

Create `services/payments/.dockerignore`:

```
target/
```

- [ ] **Step 8: Verify the image builds**

Run: `docker build -t payments:local services/payments`
Expected: build completes successfully.

- [ ] **Step 9: Commit**

```bash
git add services/payments/pom.xml services/payments/src/main/resources/application.yml services/payments/src/test/java/com/microwave/payments/ActuatorHealthIT.java services/payments/Dockerfile services/payments/.dockerignore
git commit -m "feat(payments): add actuator health endpoint and Dockerfile"
```

---

### Task 4: `docker-compose.yml` — orchestrate all 6 containers

**Files:**
- Create: `docker-compose.yml` (repo root)

**Interfaces:**
- Consumes: the 3 Dockerfiles from Tasks 1–3 (`services/catalog/Dockerfile`, `services/orders/Dockerfile`, `services/payments/Dockerfile`), and each service's `/actuator/health` endpoint from Tasks 1–3.
- Produces: 6 running containers reachable at `localhost:8081` (catalog), `localhost:8082` (payments), `localhost:8083` (orders), with `orders` only accepting traffic once `catalog`, `payments`, and `orders-db` all report healthy.

- [ ] **Step 1: Write `docker-compose.yml`**

Create `docker-compose.yml` at the repo root:

```yaml
services:
  catalog-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: catalog_db
      POSTGRES_USER: catalog
      POSTGRES_PASSWORD: catalog
    volumes:
      - catalog-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U catalog -d catalog_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  orders-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: orders_db
      POSTGRES_USER: orders
      POSTGRES_PASSWORD: orders
    volumes:
      - orders-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U orders -d orders_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  payments-db:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: payments_db
      POSTGRES_USER: payments
      POSTGRES_PASSWORD: payments
    volumes:
      - payments-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U payments -d payments_db"]
      interval: 5s
      timeout: 5s
      retries: 10

  catalog:
    build: ./services/catalog
    ports:
      - "8081:8081"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://catalog-db:5432/catalog_db
      SPRING_DATASOURCE_USERNAME: catalog
      SPRING_DATASOURCE_PASSWORD: catalog
    depends_on:
      catalog-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 30s

  payments:
    build: ./services/payments
    ports:
      - "8082:8082"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://payments-db:5432/payments_db
      SPRING_DATASOURCE_USERNAME: payments
      SPRING_DATASOURCE_PASSWORD: payments
    depends_on:
      payments-db:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 30s

  orders:
    build: ./services/orders
    ports:
      - "8083:8083"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://orders-db:5432/orders_db
      SPRING_DATASOURCE_USERNAME: orders
      SPRING_DATASOURCE_PASSWORD: orders
      CATALOG_SERVICE_URL: http://catalog:8081
      PAYMENTS_SERVICE_URL: http://payments:8082
    depends_on:
      orders-db:
        condition: service_healthy
      catalog:
        condition: service_healthy
      payments:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]
      interval: 5s
      timeout: 5s
      retries: 10
      start_period: 30s

volumes:
  catalog-db-data:
  orders-db-data:
  payments-db-data:
```

- [ ] **Step 2: Validate the compose file syntax**

Run: `docker-compose config`
Expected: prints the fully resolved configuration with no errors (confirms YAML is valid and references resolve).

- [ ] **Step 3: Bring the stack up and verify all containers become healthy**

Run: `docker-compose up --build -d`
Then: `docker-compose ps`
Expected: all 6 services show `running` (or equivalent), and `catalog`, `payments`, `orders`, and the three `-db` containers show `healthy` once `docker-compose ps` is re-run after ~30–60s. If a service shows `unhealthy` or exits, run `docker-compose logs <service>` to diagnose before proceeding.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat: add docker-compose orchestration for catalog, orders, payments"
```

---

### Task 5: End-to-end verification of the full Phase 1 flow via Docker Compose

**Files:**
- None (verification only — no files change in this task unless a defect is found, in which case fix it in the relevant service from Tasks 1–3 and re-run this task).

**Interfaces:**
- Consumes: the running stack from Task 4 (`docker-compose up --build -d`), `POST /products` on `catalog` (port 8081), `POST /orders` on `orders` (port 8083).

- [ ] **Step 1: Start from a clean slate**

Run: `docker-compose down -v`
Run: `docker-compose up --build -d`
Wait ~30–60s, then run: `docker-compose ps`
Expected: all 6 containers healthy (same check as Task 4 Step 3, confirming a full cold start works, not just an incremental one).

- [ ] **Step 2: Create a product via `catalog`**

Run:
```bash
curl -s -X POST http://localhost:8081/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Keyboard","description":"Mechanical keyboard","price":350.00}'
```
Expected: HTTP 201 with a JSON body containing `"name":"Keyboard"` and an `"id"` field. Note the `id` value for the next step.

- [ ] **Step 3: Place an order via `orders`, referencing the product from Step 2**

Run (replace `<id>` with the value from Step 2):
```bash
curl -s -X POST http://localhost:8083/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":<id>,"quantity":1}'
```
Expected: HTTP 201 with a JSON body where `"status":"CONFIRMED"` — `orders` synchronously called `catalog` (to price the order) and `payments` (to process it), and since `350.00` is well under `PaymentSimulator`'s `10000` approval limit (`services/payments/src/main/java/com/microwave/payments/payment/util/PaymentSimulator.java`), the payment is approved and the order moves out of `CREATED` into `CONFIRMED`. If instead the response is `503` or the order stays in `CREATED`, this is TD-1 surfacing (`payments` unreachable) — check `docker-compose logs payments` and `docker-compose logs orders`.

- [ ] **Step 4: Confirm data survives a restart (without `-v`)**

Run: `docker-compose down`
Run: `docker-compose up -d` (no `--build`, no `-v`)
Wait for healthchecks, then run:
```bash
curl -s http://localhost:8081/products
```
Expected: the `Keyboard` product from Step 2 is still present in the response — proves the named volumes from Task 4 persist data across a `down`/`up` cycle.

- [ ] **Step 5: Confirm native (non-Docker) tests still pass unchanged**

Run: `mvn -f services/catalog/pom.xml verify && mvn -f services/orders/pom.xml verify && mvn -f services/payments/pom.xml verify`
Expected: all three builds pass — confirms `application.yml`'s `localhost` defaults were never touched and the native dev flow (`mise` + `mvn`, per `docs/development-setup.md`) still works exactly as before this phase.

- [ ] **Step 6: Tear down**

Run: `docker-compose down -v`

No commit for this task — it's verification only, confirming Tasks 1–4 together satisfy the roadmap's Phase 2 "done when" criterion.

---

### Task 6: Documentation updates

**Files:**
- Modify: `docs/development-setup.md`
- Modify: `docs/conventions.md`
- Modify: `docs/decision-log/tech-debts.md`
- Modify: `docs/roadmap.md`

**Interfaces:**
- None — this task only updates living docs to reflect what Tasks 1–5 built, per the project's convention that doc updates land in the same change as the decisions/patterns they describe.

- [ ] **Step 1: Add a "Running via Docker Compose" section to `docs/development-setup.md`**

Append to `docs/development-setup.md`, after the existing numbered setup steps:

```markdown
## Running via Docker Compose

Instead of running each service natively (step 5 above), the full stack — all 3 services plus their databases — can run entirely in containers:

```bash
docker-compose up --build   # first run, or after code changes
docker-compose up           # subsequent runs, no rebuild
docker-compose down         # stop, keep data
docker-compose down -v      # stop and wipe all data (named volumes)
```

Services are reachable at the same ports as native mode: `catalog` on `8081`, `payments` on `8082`, `orders` on `8083`. `docker-compose` picks up `DOCKER_HOST` from `mise.local.toml` automatically (same socket Testcontainers already uses), so no extra configuration is needed beyond what step 3 above already sets up.
```

- [ ] **Step 2: Document the containerization pattern in `docs/conventions.md`**

Add a new section to `docs/conventions.md`, after the existing "## Testing" section and before "## Out of scope for this file":

```markdown
## Containerization

Each service has a multi-stage `Dockerfile` (`services/<service>/Dockerfile`): a `maven:3.9.16-eclipse-temurin-25` build stage running `mvn package -DskipTests`, and an `eclipse-temurin:25-jre` runtime stage that only copies the built `.jar`. Tests never run inside the image build — that's already covered by CI (Phase 1.1) on every PR.

Configuration for containers is env-var only — no `application.yml` placeholders. `docker-compose.yml` sets `SPRING_DATASOURCE_URL`/`_USERNAME`/`_PASSWORD` and any custom service-to-service URL property (e.g. `CATALOG_SERVICE_URL` → `catalog.service.url`) via Spring Boot's relaxed env-var binding. `application.yml` keeps its `localhost` defaults, so native (`mise`/IDE) runs are unaffected.

Every service exposes `GET /actuator/health` via `spring-boot-starter-actuator`, with `management.endpoints.web.exposure.include=health` — no other actuator endpoint is exposed, since app ports are published to the host. New services should add this from the start; it's used for `docker-compose` healthchecks and `depends_on: condition: service_healthy` ordering, and doubles as the base for Phase 4's Kubernetes liveness/readiness probes.
```

- [ ] **Step 3: Add TD-3 and TD-4 to `docs/decision-log/tech-debts.md`**

In `docs/decision-log/tech-debts.md`, add two new entries under `## Open`, after TD-2 and before `## Resolved`:

```markdown
### TD-3 — App ports published directly to the host, no gateway in front

**Introduced in:** Phase 2
**Where:** `docker-compose.yml` — `catalog`, `orders`, `payments` port mappings

All three services' ports (8081/8082/8083) are published directly to the host so the existing Postman/curl-based testing flow keeps working. There's no API Gateway or reverse proxy in front of them.

**Why it exists:** `docs/roadmap.md`'s "Deferred decisions" section already defers the API Gateway to Phase 4, where it pairs naturally with Kubernetes Ingress. Phase 2 continues that same deferral — it doesn't introduce a new gap, just makes the existing one visible at the container-networking level.

**Planned resolution:** When the API Gateway lands in Phase 4, direct host port publishing is replaced by routing through the gateway/ingress.

### TD-4 — DB credentials hardcoded in `docker-compose.yml`

**Introduced in:** Phase 2
**Where:** `docker-compose.yml` — `catalog-db`, `orders-db`, `payments-db`, and the corresponding `SPRING_DATASOURCE_*` env vars on each service

Database usernames/passwords are hardcoded directly in `docker-compose.yml`, at the same security level as the plaintext credentials already present in each service's `application.yml` since Phase 1.

**Why it exists:** these aren't real secrets (local learning-project Postgres credentials), so introducing `.env`-based indirection now would add complexity without reducing any actual risk. See the Phase 2 design spec's rejected-approaches discussion for the full reasoning.

**Planned resolution:** `docs/roadmap.md`'s Phase 4 scope already includes Kubernetes `ConfigMaps/Secrets` — that's when real secret management is introduced, replacing both this and Phase 1's `application.yml` credentials.
```

- [ ] **Step 4: Mark Phase 2 complete in `docs/roadmap.md`**

In `docs/roadmap.md`, update the Phase 2 section's status line — replace:

```markdown
### Phase 2 — Containerization

Dockerfile per service + docker-compose orchestrating all services and databases.
```

with:

```markdown
### Phase 2 — Containerization

**Status:** Complete (2026-08-11). See [`docs/superpowers/specs/2026-08-11-phase2-containerization-design.md`](superpowers/specs/2026-08-11-phase2-containerization-design.md) and [`docs/superpowers/plans/2026-08-11-phase2-containerization.md`](superpowers/plans/2026-08-11-phase2-containerization.md) for the design and plan it was built from.

Dockerfile per service + docker-compose orchestrating all services and databases.
```

Also update the "## Next step" paragraph at the bottom of `docs/roadmap.md` to reflect that Phase 2 is complete and Phase 3 is next, following the same phrasing pattern already used there for Phase 1 and Phase 1.1.

- [ ] **Step 5: Commit**

```bash
git add docs/development-setup.md docs/conventions.md docs/decision-log/tech-debts.md docs/roadmap.md
git commit -m "docs: record Phase 2 containerization conventions, tech debts, and roadmap status"
```

---

## Final step: open the PR

After Task 6, push the `phase2-containerization` branch and open a PR against `main`, following the same flow used for Phase 1.1 (`ed5d48e` merged PR #3). Do not push or open the PR without the user's explicit go-ahead in that moment — a prior approval earlier in this plan doesn't carry over.
