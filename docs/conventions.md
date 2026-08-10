# Conventions

Living description of the shared code architecture across services — the "how," as opposed to `docs/decision-log/` (the "why not" / "what's imperfect"). Update this when a new service is added or an existing pattern changes; new services should follow this from the start rather than being refactored into alignment later.

## Package layout

Each service follows the same shape, rooted at `com.microwave.<service>`:

```
<service>/
├── config/              # cross-cutting Spring config (e.g. OpenApiConfig)
├── error/                # cross-cutting error handling (GlobalExceptionHandler, ValidationProblemDetail, FieldErrorDetail)
└── <domain>/             # one package per domain concept (e.g. product, order, payment)
    ├── <Domain>.java             # JPA entity
    ├── <Domain>Repository.java
    ├── <Domain>Service.java
    ├── <Domain>Controller.java
    ├── dto/                       # request/response records for this domain
    └── exceptions/                # domain-specific exceptions (e.g. ProductNotFoundException)
```

`orders` additionally has `catalog/` and `payments/` sub-packages holding the DTOs/clients used to call those services — one sub-package per upstream service it integrates with, mirroring the boundary rather than dumping everything into `order/`.

## DTOs

Request/response DTOs are Java `record`s. Responses built from an entity expose a static factory method, `from(...)`:

```java
public record OrderResponse(Long id, Long productId, int quantity, BigDecimal totalAmount, OrderStatus status) {
  public static OrderResponse from(Order order) {
    return new OrderResponse(order.getId(), order.getProductId(), order.getQuantity(), order.getTotalAmount(), order.getStatus());
  }
}
```

No mapping library is used — see [`RA-2`](decision-log/rejected-approaches.md) for why MapStruct was declined. This `from(...)` convention is what fills that gap; keep using it rather than reintroducing a mapper.

## Error handling

- Domain-specific exceptions (e.g. `ProductNotFoundException`, `OrderNotFoundException`) live in `<domain>/exceptions/`.
- Each service has its own `error/` package with a `GlobalExceptionHandler` (`@RestControllerAdvice`) that maps each domain exception to a `ProblemDetail` with the appropriate HTTP status, plus a shared handler for `MethodArgumentNotValidException` that returns a `ValidationProblemDetail` (a `ProblemDetail` subtype carrying a list of `FieldErrorDetail`).

**`GlobalExceptionHandler`, `ValidationProblemDetail`, and `FieldErrorDetail` are intentionally duplicated per service**, not extracted into a shared library. This is a deliberate choice, not accidental drift: it preserves each service's independence (own deploy, own evolution, no shared-lib coupling), consistent with the "database per service" boundary from Phase 1. Don't extract this into a shared module without revisiting that reasoning first.

## Service layer

Every service has a `<Domain>Service` class sitting between the controller and the repository, holding orchestration and business rules — the controller stays thin (request/response mapping + delegating to the service), the repository stays a pure data-access interface. This was retrofitted into `catalog` and `payments` for consistency with `orders` (see `f71d0f3`); new services should have this from the start rather than needing the same retrofit.

## Testing

Per the Phase 1 design (see [`docs/superpowers/specs/2026-07-31-phase1-foundation-design.md`](superpowers/specs/2026-07-31-phase1-foundation-design.md)), each service is tested across these layers, and new services should keep the same shape:

- **Unit tests** for service-layer logic.
- **`MockMvc`** for controller-layer tests (request/response shape, validation, error mapping).
- **Testcontainers** for integration tests against a real Postgres instance (wired via `@ServiceConnection`, not manual `@DynamicPropertySource` — see `49c0124`).
- **WireMock** for Feign client contract tests, where a service calls another service synchronously (e.g. `orders` → `catalog`, `orders` → `payments`).

## Out of scope for this file

- Anything specific to a single phase — that belongs in the relevant `docs/superpowers/specs/` or `docs/superpowers/plans/` entry, not here.
- Code formatting / lint rules — that's IDE/tooling config, not a documented convention.
- Branch naming, commit message format — covered by repo-wide git conventions, not project architecture.
