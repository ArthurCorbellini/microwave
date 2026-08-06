# Microwave

A learning project: an e-commerce system built incrementally to practice microservices in Java, messaging with RabbitMQ/Kafka, containerization with Docker, and orchestration with Kubernetes.

See [docs/roadmap.md](docs/roadmap.md) for the full roadmap, [docs/tech-debt.md](docs/tech-debt.md) for known limitations, and [docs/development-setup.md](docs/development-setup.md) for how to get a working dev environment.

## Phase 1 — Microservices foundation

Three independent Spring Boot services, each with its own PostgreSQL database, communicating over synchronous REST:

- `services/catalog` (port 8081) — product catalog
- `services/payments` (port 8082) — simulated payment processing
- `services/orders` (port 8083) — order orchestration (calls `catalog` and `payments` via OpenFeign)

### Requirements

- Java 25 and Maven, pinned via [mise](https://mise.jdx.dev) — see [docs/development-setup.md](docs/development-setup.md)
- Docker or Podman (required by Testcontainers for integration tests)

### Running the tests

Each service is an independent Maven module. Use `verify`, not `test` — Testcontainers-backed integration tests (`*IT` classes) are bound to the `maven-failsafe-plugin` and are skipped by Surefire's default `test` phase:

```bash
mvn -f services/catalog/pom.xml verify
mvn -f services/payments/pom.xml verify
mvn -f services/orders/pom.xml verify
```

### Running a service locally

Each service expects a local PostgreSQL database matching its `application.yml` datasource config (see each service's `src/main/resources/application.yml`). Docker Compose support for running all services and databases together is planned for Phase 2.

```bash
mvn -f services/catalog/pom.xml spring-boot:run
```
