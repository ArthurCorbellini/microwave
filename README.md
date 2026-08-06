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

### Trying the end-to-end flow

This is a **one-off manual verification setup**, not the regular dev workflow — tests get their own throwaway databases from Testcontainers (see [docs/development-setup.md](docs/development-setup.md)), so nothing here is needed to run `mvn verify`. Use it only when you want to exercise the real `catalog → orders → payments` chain over HTTP by hand.

All three services point at the same `localhost:5432`, so one PostgreSQL server hosting three databases is enough. Substitute `docker` for `podman-remote` if that's your container engine. Note each `CREATE DATABASE` needs its own `-c` — `psql` wraps a multi-statement `-c` in a transaction, and `CREATE DATABASE` cannot run inside one.

```bash
podman-remote run -d --name microwave-e2e-postgres -p 5432:5432 \
  -e POSTGRES_PASSWORD=postgres docker.io/library/postgres:17-alpine

# wait until it accepts connections
podman-remote exec microwave-e2e-postgres pg_isready

podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE USER catalog WITH PASSWORD 'catalog'"
podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE USER payments WITH PASSWORD 'payments'"
podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE USER orders WITH PASSWORD 'orders'"

podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE DATABASE catalog_db OWNER catalog"
podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE DATABASE payments_db OWNER payments"
podman-remote exec microwave-e2e-postgres psql -U postgres -c "CREATE DATABASE orders_db OWNER orders"
```

Then start each service in its own terminal (`ddl-auto: update` creates the tables on first boot) and wait for all three to log `Started ...Application`:

```bash
mvn -f services/catalog/pom.xml spring-boot:run
mvn -f services/payments/pom.xml spring-boot:run
mvn -f services/orders/pom.xml spring-boot:run
```

Now walk the flow. Creating an order makes `orders` call `catalog` for the product price and then `payments` to charge the total — $100.00 × 2 is well under the simulator's $10,000 approval limit, so the order comes back `CONFIRMED`:

```bash
curl -s -X POST localhost:8081/products -H "Content-Type: application/json" \
  -d '{"name":"Keyboard","description":"Mechanical keyboard","price":100.00}'
# {"id":1,"name":"Keyboard","description":"Mechanical keyboard","price":100.00}

curl -s -X POST localhost:8083/orders -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'
# {"id":1,"productId":1,"quantity":2,"totalAmount":200.00,"status":"CONFIRMED"}

curl -s localhost:8083/orders/1
# {"id":1,"productId":1,"quantity":2,"totalAmount":200.00,"status":"CONFIRMED"}
```

`payments` recorded the charge against that order id, in its own database:

```bash
curl -s localhost:8082/payments/1
# {"id":1,"orderId":1,"amount":200.00,"status":"APPROVED"}
```

Tear it all down afterwards — stop the three `spring-boot:run` processes, then:

```bash
podman-remote stop microwave-e2e-postgres
podman-remote rm microwave-e2e-postgres
```
