# Order Lifecycle Saga

Living description of what `POST /orders` sets off — the choreographed saga spanning `catalog`, `inventory`, and `payments`, traced end to end. Complements [`docs/architecture.md`](../architecture.md), which shows *which* services talk to each other (static topology); this shows *when* — the temporal sequence of one request, including its decline/compensation branch.

Update this whenever a phase changes the flow (new steps, new compensations) — same living-doc obligation as `docs/architecture.md`.

## `POST /orders`

```mermaid
sequenceDiagram
    participant Client
    participant Orders as orders
    participant Catalog as catalog
    participant Inventory as inventory
    participant Payments as payments

    Client->>Orders: POST /orders
    Orders->>Catalog: GET /products/{id} (REST, sync)
    Catalog-->>Orders: price
    Orders->>Orders: persist Order{CREATED}
    Orders-->>Client: 201 {status: CREATED}

    Note over Orders,Payments: everything below is async —<br/>the client already has its 201 and isn't waiting

    Orders->>Inventory: ReserveStock (RabbitMQ command)
    Inventory-->>Orders: InventoryReserved (RabbitMQ reply)

    alt reserved = false
        Orders->>Orders: status = REJECTED
    else reserved = true
        Orders->>Payments: ChargePayment (RabbitMQ command)
        Payments-->>Orders: PaymentProcessed (RabbitMQ reply)

        alt approved = true
            Orders->>Orders: status = CONFIRMED
        else approved = false
            Orders->>Inventory: ReleaseStock (RabbitMQ command, fire-and-forget)
            Orders->>Orders: status = REJECTED
        end
    end

    Client->>Orders: GET /orders/{id} (polling)
    Orders-->>Client: current status
```

- `orders` → `catalog` is the only synchronous hop, and it happens *before* the order even exists — the client's `201` doesn't wait on anything after that.
- Both RabbitMQ replies (`InventoryReserved`, `PaymentProcessed`) are guarded by the same rule: only act if the order is still `CREATED` (`Order.isSettled()`). Order stays `CREATED` for the entire window between the two replies, so one guard protects both handlers without needing a dedicated intermediate status.
- `ReleaseStock` is fire-and-forget — `inventory` never replies to it, and `orders` doesn't wait for it before persisting `REJECTED`.

## Why this is a saga

A **saga** is a sequence of local transactions, each owned by a different service, with a compensating action that undoes an already-completed step if a later one fails — used instead of one distributed ACID transaction. Here: `orders` creates the order, `inventory` reserves stock, `payments` charges; if charging is declined, `ReleaseStock` compensates the reservation. `orders`' own step has no compensation of its own — the `Order` row is never deleted, it just receives its terminal status (`REJECTED`), acting as the saga's anchor/record.

This project implements sagas via **choreography**, not orchestration: each service reacts to the previous step's reply and decides its own next step, with no central coordinator — see `docs/roadmap.md`'s Phase 4 entry, which frames this as "saga compensation," not a two-phase commit.
