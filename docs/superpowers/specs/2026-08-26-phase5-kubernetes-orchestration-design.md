# Kubernetes Orchestration — Design

**Date:** 2026-08-26
**Status:** approved, pending implementation plan.
**Roadmap phase:** Phase 5 — Kubernetes orchestration

## Purpose

Introduce Kubernetes manifests (Deployments, Services, ConfigMaps, Secrets, PersistentVolumeClaims) for all services, databases, and brokers, so the full Phase 4 stack can run on a local Kind cluster via `kubectl apply`, alongside — not replacing — `docker-compose.yml`.

## Motivation

Phase 4 completed the messaging migration; the system still runs entirely via `docker-compose up` on a single Docker/Podman host. This phase introduces real container orchestration — service discovery via cluster DNS, self-healing (Pod restarts, readiness gating), and declarative horizontal scaling — none of which `docker-compose` provides. It's also the foundation Phase 6 (Kubernetes Ingress) and Phase 9 (Terraform + managed cloud cluster, reusing these same manifests) build on directly.

## Scope

**In scope:**
- K8s manifests for all 5 app services, 5 Postgres databases, RabbitMQ, and Kafka.
- A dedicated `microwave` namespace.
- `Secret`/`ConfigMap` objects carrying each service's DB credentials and non-sensitive config, closing the K8s-path portion of `TD-4` (see "Tech debt updates").
- `NodePort` Services for the 5 app services (8081-8085) and RabbitMQ's management UI (15672), preserving today's direct-access behavior for Postman/curl testing.
- `replicas: 2` declared on the 5 app service Deployments, demonstrating declarative horizontal scaling.
- Readiness/liveness probes on the 5 app services, backed by their existing `/actuator/health` endpoints.
- A Kind-based CI job: spins up a cluster, builds and loads all 5 images, applies the manifests, waits for all Pods to become `Ready`, and checks each app service's health endpoint via `NodePort`.
- Updating the env vars that currently point at compose container names (`CATALOG_SERVICE_URL`, `SPRING_DATASOURCE_URL`, `SPRING_RABBITMQ_HOST`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`) to point at K8s Service DNS names instead, inside the K8s manifests only.

**Out of scope (explicitly deferred or rejected):**
- `docker-compose.yml` is not removed or changed — it stays as a parallel, fully maintained option for quick local dev (duplication accepted deliberately, to keep a lighter-weight path available).
- Kustomize/Helm — rejected for this phase (`RA-3`); plain YAML manifests only.
- Ingress / API Gateway — still Phase 6.
- `HorizontalPodAutoscaler` / metrics-server-based autoscaling — not introduced; scaling is demonstrated via a declared `replicas: 2`, not dynamic scaling.
- `StatefulSet` for Postgres/RabbitMQ/Kafka — all three stay single-instance, no clustering, so a `Deployment` (+ `PersistentVolumeClaim` where a volume already exists in compose) is sufficient; a `StatefulSet`'s stable network identity and ordered rollout guarantees aren't needed for a single replica.
- A full cross-service end-to-end smoke test in CI (create order → payment → inventory reservation) — the new CI job verifies the cluster comes up healthy, not that the business flow works; the business-flow gap stays `TD-8`'s scope, deferred to Phase 8.
- External secret managers (Vault, cloud secret stores, sealed-secrets) — plain K8s `Secret` objects only.
- Any change to `docs/roadmap.md`'s existing Phase 6/8/9 sequencing.

## Design

### Cluster tooling

**Kind**, not Minikube. Reasoning: Kind's nodes are plain containers with no cluster-specific "addon" layer (dashboard, ingress, metrics-server) — everything needed is applied as ordinary manifests from the start. Phase 9 explicitly plans to take "the same manifests" from this phase and deploy them onto a Terraform-provisioned cloud cluster; relying on Minikube-specific addons now would mean redoing that setup as plain manifests later. Kind also has better Podman-provider support today than Minikube.

### Namespace

All objects live in a dedicated `microwave` namespace (`k8s/namespace.yaml`), not `default` — keeps the cluster organized and avoids collisions if the local Kind cluster is ever reused for something else.

### Manifest layout

Mirrors the existing `services/<service>/` per-service layout already used for source code and Dockerfiles:

```
k8s/
  namespace.yaml
  catalog/            deployment.yaml, service.yaml, configmap.yaml, secret.yaml
  catalog-db/         deployment.yaml, service.yaml, pvc.yaml, secret.yaml
  orders/             ...
  orders-db/          ...
  payments/           ...
  payments-db/        ...
  inventory/          ...
  inventory-db/       ...
  notifications/      ...
  notifications-db/   ...
  rabbitmq/           deployment.yaml, service.yaml
  kafka/              deployment.yaml, service.yaml
```

Applied with `kubectl apply -R -f k8s/` (recursive — plain `kubectl apply -f` doesn't descend into subdirectories).

### Compose → K8s object mapping

| Compose today | K8s equivalent | Notes |
|---|---|---|
| `catalog`/`orders`/`payments`/`inventory`/`notifications` service | `Deployment` (`replicas: 2`) + `Service` (`NodePort`, same 8081-8085 ports) | Readiness/liveness probes hit `/actuator/health` |
| `*-db` (5× Postgres) | `Deployment` (`replicas: 1`) + `Service` (`ClusterIP`, internal only — no host port today) + `PersistentVolumeClaim` | No `StatefulSet` — single instance, no ordered identity needed |
| `rabbitmq` | `Deployment` (`replicas: 1`) + `Service` (`ClusterIP` for AMQP 5672, `NodePort` for management UI 15672) | No volume today, none added |
| `kafka` | `Deployment` (`replicas: 1`) + `Service` (`ClusterIP` only, port 9092 — not published to host today either) | No volume today, none added |
| `environment:` (non-sensitive) | `ConfigMap`, mounted as env vars | |
| `environment:` (DB credentials) | `Secret`, mounted as env vars | One per service, mirrors "database per service" |
| `depends_on: condition: service_healthy` | **No equivalent.** | K8s has no native startup-ordering primitive; each service's existing retry/backoff toward its dependencies (Spring datasource retry, RabbitMQ/Kafka client reconnect) covers this instead. Called out here as a deliberate design point, not a gap to close. |
| `volumes:` (Postgres data) | `PersistentVolumeClaim`, default Kind storage class | |

### Service discovery

Internal service-to-service calls switch from compose's Docker network DNS (`catalog`, `catalog-db`, `rabbitmq`, `kafka`) to K8s's cluster DNS, which resolves equivalent short names within a namespace (`catalog`, `catalog-db`, etc., or fully qualified as `catalog.microwave.svc.cluster.local`). Every environment variable that currently points at a compose service name gets the equivalent K8s Service name in its manifest instead — no application code changes, since these are already externalized as env vars.

### Image build and load

Dockerfiles are unchanged from Phase 2. Since builds happen via Podman (not Docker) in this environment, getting an image into Kind is: `podman build` → `podman save -o <service>.tar` → `kind load image-archive <service>.tar --name <cluster-name>`, once per service. This replaces `docker-compose build`'s implicit build step; the Dockerfiles themselves don't change.

### Scaling

The 5 app service Deployments declare `replicas: 2` directly in their manifests — a declarative baseline matching real production practice (redundancy via infrastructure-as-code, not ad-hoc `kubectl scale`, which would silently drift from the manifest on the next `apply`). Postgres/RabbitMQ/Kafka stay at `replicas: 1` — no clustering support exists for any of them today, and adding it is out of scope for this phase.

### CI: Kind smoke test

A new GitHub Actions job, alongside the existing `test` and `docker-build` matrix jobs:
1. Installs `kind`, creates a cluster.
2. Builds all 5 service images and loads them into the cluster.
3. Applies `k8s/` recursively.
4. Waits for every Pod in the `microwave` namespace to reach `Ready` (`kubectl wait --for=condition=Ready pods --all -n microwave --timeout=...`).
5. Curls each app service's `/actuator/health` via its `NodePort`.

This is a deployment health check, not a business-flow test — it doesn't create an order or exercise messaging. Full cross-service business-flow verification in CI remains `TD-8`'s scope, deferred to Phase 8 per that entry's existing planned resolution.

## Tech debt updates (at implementation time)

- **`TD-4`** (DB credentials hardcoded) — **resolved for the K8s path**, via per-service `Secret` objects. `docker-compose.yml` and `application.yml` are unchanged and keep plaintext local credentials — not a lingering gap, a deliberate choice, since they're not real secrets and mirroring Secret-style indirection there wouldn't reduce any actual risk (same reasoning `TD-4`'s original "Why it exists" already gives). Record this as an explicit `Note:` on the `Resolved` entry, the same pattern `TD-5`'s resolution already uses.
- **`TD-3`** (ports published directly, no gateway) — **stays open, unchanged in nature.** `NodePort` Services replace `docker-compose`'s host port publishing, but the effect is identical: every service is still directly reachable with no gateway in front. Its "Where" section gains the K8s `NodePort` Services alongside the existing compose port mappings; its planned resolution (Phase 6 partial, Phase 8 full) is unchanged.
- **`TD-8`** (no automated end-to-end verification) — **unchanged.** The new CI Kind job checks deployment health, not the business flow; it doesn't touch `TD-8`'s gap or its planned resolution (Phase 8).

## Documentation updates (at implementation time)

- `docs/conventions.md`: add a section documenting the K8s manifest conventions (per-component `k8s/<name>/` layout, `Secret`/`ConfigMap` split, `NodePort` vs `ClusterIP` decision rule).
- `docs/decision-log/tech-debts.md`: move `TD-4` to `## Resolved` (with the `Note:` above) and extend `TD-3`'s "Where" section — in the same PR as the code, not before.
- `docs/roadmap.md`: mark Phase 5 complete once done, linking to this spec and its plan.
- `docs/architecture.md`: the topology diagram itself doesn't change (same services/edges) — add a short note that, from Phase 5 on, this diagram runs on a local Kind cluster rather than directly via `docker-compose`, without altering the diagram's shape.
- `docs/development-setup.md`: document the Kind + `kubectl` local workflow alongside the existing `docker-compose` instructions.

## Testing

- The CI Kind smoke test (see "Design" above) is the primary new automated coverage this phase adds.
- Manual verification (per the roadmap's "Done when"): the full Phase 4 end-to-end flow (create order → inventory reservation → payment → confirmation) exercised by hand against the Kind cluster, the same way each prior phase's manual checklist has worked (`TD-8`).
- No new unit/integration tests at the application code level — this phase touches infrastructure/deployment only; no service's business logic changes.

## Error handling

No new application-level error handling. The one K8s-specific behavior worth calling out explicitly: since K8s has no `depends_on`-equivalent, Pods can start in any order — each service's existing reconnect/retry behavior toward its datasource and message brokers (already relied upon across compose restarts) is what keeps a service healthy while a dependency is still starting, not any new orchestration-level ordering.
