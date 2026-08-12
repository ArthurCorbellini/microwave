# Containerization — Design

**Date:** 2026-08-11
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 2 — Containerization

## Purpose

Package each of the three Phase 1 services (`catalog`, `orders`, `payments`) as a Docker image, and orchestrate them plus their Postgres databases via a single `docker-compose.yml`, so the full Phase 1 flow (create a product, place an order, simulate a payment) comes up end-to-end with just `docker-compose up` — nothing run manually from the IDE.

## Motivation

Phase 1 and 1.1 run everything natively (via `mise` + `mvn`) with services and databases reachable at `localhost`. This phase introduces the packaging and inter-container networking layer needed before Phase 3 (new services `inventory`/`notifications`) and Phase 4 (Kubernetes) build on top of it.

## Scope

- One multi-stage `Dockerfile` per service (`services/<service>/Dockerfile`).
- One root-level `docker-compose.yml` orchestrating 6 containers: `catalog`, `orders`, `payments`, and one Postgres container per service (`catalog-db`, `orders-db`, `payments-db`).
- Configuration switched from `localhost` to inter-container hostnames via environment variables injected by `docker-compose.yml` — no changes to `application.yml` defaults.
- `spring-boot-starter-actuator` added to all three services, exposing only `/actuator/health`, used for `docker-compose` healthchecks and `depends_on: condition: service_healthy` ordering.
- Named volumes for each Postgres container, so data survives `docker-compose down` (not `-v`).
- All three app ports (8081/8082/8083) published to the host, preserving the existing Postman/curl testing flow.

Out of scope (explicitly deferred, not part of this design):
- CI building/pushing Docker images — a future extension of Phase 1.1, not required by this phase's "done when."
- `.env` file / secrets management for DB credentials — credentials stay hardcoded in `docker-compose.yml`, at the same security level as today's `application.yml`. Real secret management (Kubernetes Secrets) is already on the roadmap for Phase 4; see TD-4 below.
- An API Gateway or any change to direct host port exposure — deferred to Phase 4 per the roadmap's existing "Deferred decisions" section; see TD-3 below.
- Multi-item orders, new services, or any Phase 1 domain change — this phase is packaging/networking only.

## Design

### Dockerfile (per service)

Multi-stage build, so `docker-compose up` alone builds from source — no local `mvn package` step required:

```dockerfile
# Stage 1: build
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

# Stage 2: runtime
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- `-DskipTests`: the image build isn't where tests run — that's already covered by CI (Phase 1.1) on every PR. Re-running the full Testcontainers suite inside the image build would duplicate that and slow down `docker-compose up`.
- Copying `pom.xml` and running `dependency:go-offline` before copying `src` lets Docker cache the dependency-download layer across rebuilds when only source changes.
- Each service gets a `.dockerignore` (`target/`, `.git/`, `*.md`) to keep build context small.

Rejected alternative: build the jar locally (`mvn package`) and `COPY` it into a single-stage image. Faster iteration, but requires a manual step before `docker-compose up`, which conflicts directly with this phase's "done when" criterion in `docs/roadmap.md`.

### Configuration via environment variables

No changes to any `application.yml` — Spring Boot's relaxed env-var binding overrides properties directly. `docker-compose.yml` sets, per service:

- `catalog` / `payments` / `orders`: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` pointing at that service's own DB container (e.g. `jdbc:postgresql://catalog-db:5432/catalog_db`).
- `orders` additionally: `CATALOG_SERVICE_URL=http://catalog:8081`, `PAYMENTS_SERVICE_URL=http://payments:8082` (maps to `catalog.service.url` / `payments.service.url`).

Running via `mvn test` or the IDE (no Docker) is unaffected — `application.yml`'s `localhost` defaults still apply.

### Health checks and startup ordering

`orders` calls `catalog` and `payments` synchronously (Feign); without readiness gating, the first request after `docker-compose up` could race a not-yet-ready dependency. All three services add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

with `management.endpoints.web.exposure.include=health` in `application.yml` (only `/actuator/health` is exposed — ports are published to the host, so the full actuator surface shouldn't be).

`docker-compose.yml`:
- Postgres containers: healthcheck via `pg_isready`.
- `catalog` / `payments`: healthcheck via `curl -f http://localhost:8081/actuator/health` (resp. 8082), `depends_on: condition: service_healthy` on their own DB.
- `orders`: `depends_on: condition: service_healthy` on `orders-db`, `catalog`, and `payments`.

This also lays groundwork for Phase 4's Kubernetes liveness/readiness probes, which conventionally reuse the same actuator endpoint.

### Data persistence

Named volumes, one per Postgres container (`catalog-db-data`, `orders-db-data`, `payments-db-data`), declared at the bottom of `docker-compose.yml`. Data survives `docker-compose down`; only `docker-compose down -v` removes it.

### Networking and port exposure

Single default bridge network created by `docker-compose`. Services address each other by service name (`catalog`, `orders`, `payments`, `catalog-db`, etc.) — no hardcoded IPs. All three app ports are published to the host (`8081:8081`, `8082:8082`, `8083:8083`) so the existing Postman/curl-based testing flow keeps working unchanged.

### Documentation updates

- `docs/development-setup.md`: new "Running via Docker Compose" section (`docker-compose up`, `docker-compose up --build` after code changes, `docker-compose down` / `down -v`).
- `docs/conventions.md`: new section documenting the per-service multi-stage Dockerfile pattern, the env-var-based configuration convention, and `/actuator/health` as a new shared convention across services.
- `docs/decision-log/tech-debts.md`: two new entries.
  - **TD-3** — app ports published directly to the host with no gateway in front; planned resolution when the API Gateway lands in Phase 4 (already noted as a deferred decision in `docs/roadmap.md`).
  - **TD-4** — DB credentials hardcoded in `docker-compose.yml`, same security level as today's `application.yml`; planned resolution when Kubernetes Secrets are introduced in Phase 4 (`docs/roadmap.md` already lists `ConfigMaps/Secrets` under Phase 4's scope).
- `docs/roadmap.md`: mark Phase 2 complete once done, linking to this spec and its plan.

## Testing

No new automated test suite — this phase's own verification is behavioral, run manually against the real compose stack:

1. `docker-compose up --build` from a clean state (`docker-compose down -v` first) brings up all 6 containers with no manual step outside the IDE.
2. All three healthchecks report healthy; `orders` doesn't start accepting traffic before `catalog`/`payments`/`orders-db` are healthy.
3. The full Phase 1 flow works end-to-end via HTTP against the published host ports: create a product (`catalog`), place an order referencing it (`orders`), confirm the simulated payment call reaches `payments` and the order's status updates accordingly.
4. `docker-compose down` followed by `docker-compose up` (no `--build`, no `-v`) preserves previously created data (volumes).
5. Running `mvn -f services/<service>/pom.xml test` outside Docker still passes unchanged, confirming `application.yml` defaults weren't touched.

## Error handling

None beyond what already exists in each service (Phase 1's `GlobalExceptionHandler`) and what Docker/Compose provide natively (healthcheck-gated startup ordering). TD-1 (orders stuck in `CREATED` if `payments` is unreachable mid-flow) is unaffected by this phase — still explicitly deferred to Phase 3.
