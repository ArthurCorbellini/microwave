# Phase 5 Kubernetes Orchestration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the full Phase 4 stack (5 app services, 5 Postgres databases, RabbitMQ, Kafka) on a local Kind cluster via plain K8s manifests, alongside — not replacing — `docker-compose.yml`.

**Architecture:** Each compose service becomes a `Deployment` + `Service` pair under a dedicated `microwave` namespace, laid out as `k8s/<component>/` (mirroring the existing `services/<service>/` convention). App services get `replicas: 2` and `NodePort` Services (same host ports as today: 8081-8085); Postgres/RabbitMQ/Kafka stay at `replicas: 1` with `ClusterIP` Services (plus one `NodePort` for RabbitMQ's management UI). DB credentials move into `Secret` objects, non-sensitive config into `ConfigMap` objects. A new CI job spins up a Kind cluster, applies the manifests, and checks that every Pod becomes healthy.

**Tech Stack:** Kind (cluster), `kubectl` (apply), plain K8s YAML manifests (no Kustomize/Helm — see `RA-3`), Podman for local image builds (`podman build` → `podman save` → `kind load image-archive`), Docker for CI image builds (`docker build` → `kind load docker-image`, since GitHub-hosted runners have Docker natively).

**Spec:** `docs/superpowers/specs/2026-08-26-phase5-kubernetes-orchestration-design.md`

## Global Constraints

- Kind, not Minikube (portability to Phase 9, better Podman support) — see spec's "Cluster tooling".
- `docker-compose.yml` is untouched and stays as a parallel option — no changes to it in this plan.
- Plain YAML manifests only — no Kustomize, no Helm (`RA-3`).
- Dedicated `microwave` namespace for every object — never `default`.
- Manifest layout: `k8s/<component>/` (one directory per Deployment+Service unit), mirroring `services/<service>/`. Applied with `kubectl apply -R -f k8s/`.
- App services (`catalog`, `orders`, `payments`, `inventory`, `notifications`): `replicas: 2`, `Service` type `NodePort`, same host-facing ports as `docker-compose.yml` today (8081-8085).
- Postgres (×5), RabbitMQ, Kafka: `replicas: 1`, no `StatefulSet` — plain `Deployment`. Postgres Deployments use `strategy: type: Recreate` (a `ReadWriteOnce` PVC can't be mounted by two Pods at once, which a default `RollingUpdate` would attempt).
- RabbitMQ gets two Services: `rabbitmq` (`ClusterIP`, AMQP 5672, internal only — matches `docker-compose.yml` never publishing 5672 to the host) and `rabbitmq-management` (`NodePort`, UI 15672, matching today's published port).
- Kafka gets one `ClusterIP` Service only (port 9092) — not published to the host today either.
- DB credentials live in `Secret` objects (`<component>-db-credentials` for Postgres, `<component>-credentials` for the app side); non-sensitive config lives in `ConfigMap` objects (`<component>-config`). No external secret manager.
- No resource requests/limits, no `HorizontalPodAutoscaler`, no `Ingress` — all explicitly out of scope per the spec.
- The env var *values* that already point at a compose container name (e.g. `catalog-db`, `rabbitmq`, `kafka`) stay textually identical — K8s Service short names resolve the same way within a namespace, so only the mechanism (ConfigMap/Secret vs. compose `environment:`) changes, not the values.
- The new CI job verifies the cluster comes up healthy (`kubectl wait` + `/actuator/health` checks) — it does **not** exercise the order-creation business flow. That gap stays `TD-8`'s scope, deferred to Phase 8.
- Design spec: `docs/superpowers/specs/2026-08-26-phase5-kubernetes-orchestration-design.md` — refer back to it for full rationale.

---

### Task 1: Kind cluster bootstrap — config, cluster, namespace

**Files:**
- Create: `k8s/kind-config.yaml`
- Create: `k8s/namespace.yaml`

**Interfaces:**
- Produces: a running Kind cluster named `microwave`, with host ports `8081-8085` and `15672` mapped to the cluster node's `NodePort`s `30081-30085` and `30672`; a `microwave` namespace inside it. Every later task's manifests reference `namespace: microwave` and rely on these exact `NodePort` numbers.

- [ ] **Step 1: Install `kind` and `kubectl` if not already present**

Run:
```bash
curl -Lo /tmp/kind https://kind.sigs.k8s.io/dl/v0.26.0/kind-linux-amd64
chmod +x /tmp/kind
sudo mv /tmp/kind /usr/local/bin/kind
kind version
```
```bash
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
chmod +x kubectl
sudo mv kubectl /usr/local/bin/kubectl
kubectl version --client
```
Expected: both commands print a version string.

- [ ] **Step 2: Write the Kind cluster config**

Create `k8s/kind-config.yaml`:

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30081
        hostPort: 8081
      - containerPort: 30082
        hostPort: 8082
      - containerPort: 30083
        hostPort: 8083
      - containerPort: 30084
        hostPort: 8084
      - containerPort: 30085
        hostPort: 8085
      - containerPort: 30672
        hostPort: 15672
```

- [ ] **Step 3: Create the cluster (Podman provider)**

Run:
```bash
KIND_EXPERIMENTAL_PROVIDER=podman kind create cluster --name microwave --config k8s/kind-config.yaml
```
Expected: ends with `Set kubectl context to "kind-microwave"`. If this fails immediately looking for Docker, confirm `KIND_EXPERIMENTAL_PROVIDER=podman` was actually exported into the command's environment.

- [ ] **Step 4: Verify the cluster is reachable**

Run: `kubectl cluster-info --context kind-microwave`
Expected: prints the control plane and CoreDNS URLs, no errors.

- [ ] **Step 5: Write the namespace manifest**

Create `k8s/namespace.yaml`:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: microwave
```

- [ ] **Step 6: Apply and verify**

Run: `kubectl apply -f k8s/namespace.yaml`
Run: `kubectl get namespace microwave`
Expected: shows `microwave` with `STATUS: Active`.

- [ ] **Step 7: Commit**

```bash
git add k8s/kind-config.yaml k8s/namespace.yaml
git commit -m "feat(k8s): bootstrap Kind cluster config and microwave namespace"
```

---

### Task 2: Postgres databases (×5) — Deployment, Service, Secret, PVC

**Files:**
- Create: `k8s/catalog-db/secret.yaml`, `k8s/catalog-db/pvc.yaml`, `k8s/catalog-db/deployment.yaml`, `k8s/catalog-db/service.yaml`
- Create: `k8s/orders-db/secret.yaml`, `k8s/orders-db/pvc.yaml`, `k8s/orders-db/deployment.yaml`, `k8s/orders-db/service.yaml`
- Create: `k8s/payments-db/secret.yaml`, `k8s/payments-db/pvc.yaml`, `k8s/payments-db/deployment.yaml`, `k8s/payments-db/service.yaml`
- Create: `k8s/inventory-db/secret.yaml`, `k8s/inventory-db/pvc.yaml`, `k8s/inventory-db/deployment.yaml`, `k8s/inventory-db/service.yaml`
- Create: `k8s/notifications-db/secret.yaml`, `k8s/notifications-db/pvc.yaml`, `k8s/notifications-db/deployment.yaml`, `k8s/notifications-db/service.yaml`

**Interfaces:**
- Consumes: Task 1's `microwave` namespace.
- Produces: 5 `ClusterIP` Services (`catalog-db`, `orders-db`, `payments-db`, `inventory-db`, `notifications-db`), each on port `5432`, resolvable by that exact name from any Pod in the `microwave` namespace — this is what Task 5-9's app-service `ConfigMap`s point their `SPRING_DATASOURCE_URL` at.

- [ ] **Step 1: `catalog-db` manifests**

Create `k8s/catalog-db/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: catalog-db-credentials
  namespace: microwave
type: Opaque
stringData:
  POSTGRES_DB: catalog_db
  POSTGRES_USER: catalog
  POSTGRES_PASSWORD: catalog
```

Create `k8s/catalog-db/pvc.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: catalog-db-data
  namespace: microwave
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 1Gi
```

Create `k8s/catalog-db/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: catalog-db
  namespace: microwave
  labels:
    app: catalog-db
spec:
  replicas: 1
  strategy:
    # ReadWriteOnce PVC can't be mounted by two Pods at once, which a
    # default RollingUpdate would briefly attempt.
    type: Recreate
  selector:
    matchLabels:
      app: catalog-db
  template:
    metadata:
      labels:
        app: catalog-db
    spec:
      containers:
        - name: catalog-db
          image: postgres:17-alpine
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: catalog-db-credentials
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "catalog", "-d", "catalog_db"]
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: catalog-db-data
```

Create `k8s/catalog-db/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: catalog-db
  namespace: microwave
spec:
  selector:
    app: catalog-db
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 2: `orders-db` manifests**

Create `k8s/orders-db/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: orders-db-credentials
  namespace: microwave
type: Opaque
stringData:
  POSTGRES_DB: orders_db
  POSTGRES_USER: orders
  POSTGRES_PASSWORD: orders
```

Create `k8s/orders-db/pvc.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: orders-db-data
  namespace: microwave
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 1Gi
```

Create `k8s/orders-db/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders-db
  namespace: microwave
  labels:
    app: orders-db
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: orders-db
  template:
    metadata:
      labels:
        app: orders-db
    spec:
      containers:
        - name: orders-db
          image: postgres:17-alpine
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: orders-db-credentials
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "orders", "-d", "orders_db"]
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: orders-db-data
```

Create `k8s/orders-db/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: orders-db
  namespace: microwave
spec:
  selector:
    app: orders-db
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 3: `payments-db` manifests**

Create `k8s/payments-db/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: payments-db-credentials
  namespace: microwave
type: Opaque
stringData:
  POSTGRES_DB: payments_db
  POSTGRES_USER: payments
  POSTGRES_PASSWORD: payments
```

Create `k8s/payments-db/pvc.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: payments-db-data
  namespace: microwave
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 1Gi
```

Create `k8s/payments-db/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments-db
  namespace: microwave
  labels:
    app: payments-db
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: payments-db
  template:
    metadata:
      labels:
        app: payments-db
    spec:
      containers:
        - name: payments-db
          image: postgres:17-alpine
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: payments-db-credentials
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "payments", "-d", "payments_db"]
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: payments-db-data
```

Create `k8s/payments-db/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: payments-db
  namespace: microwave
spec:
  selector:
    app: payments-db
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 4: `inventory-db` manifests**

Create `k8s/inventory-db/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: inventory-db-credentials
  namespace: microwave
type: Opaque
stringData:
  POSTGRES_DB: inventory_db
  POSTGRES_USER: inventory
  POSTGRES_PASSWORD: inventory
```

Create `k8s/inventory-db/pvc.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: inventory-db-data
  namespace: microwave
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 1Gi
```

Create `k8s/inventory-db/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-db
  namespace: microwave
  labels:
    app: inventory-db
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: inventory-db
  template:
    metadata:
      labels:
        app: inventory-db
    spec:
      containers:
        - name: inventory-db
          image: postgres:17-alpine
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: inventory-db-credentials
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "inventory", "-d", "inventory_db"]
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: inventory-db-data
```

Create `k8s/inventory-db/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: inventory-db
  namespace: microwave
spec:
  selector:
    app: inventory-db
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 5: `notifications-db` manifests**

Create `k8s/notifications-db/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notifications-db-credentials
  namespace: microwave
type: Opaque
stringData:
  POSTGRES_DB: notifications_db
  POSTGRES_USER: notifications
  POSTGRES_PASSWORD: notifications
```

Create `k8s/notifications-db/pvc.yaml`:
```yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: notifications-db-data
  namespace: microwave
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 1Gi
```

Create `k8s/notifications-db/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notifications-db
  namespace: microwave
  labels:
    app: notifications-db
spec:
  replicas: 1
  strategy:
    type: Recreate
  selector:
    matchLabels:
      app: notifications-db
  template:
    metadata:
      labels:
        app: notifications-db
    spec:
      containers:
        - name: notifications-db
          image: postgres:17-alpine
          ports:
            - containerPort: 5432
          envFrom:
            - secretRef:
                name: notifications-db-credentials
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
          readinessProbe:
            exec:
              command: ["pg_isready", "-U", "notifications", "-d", "notifications_db"]
            initialDelaySeconds: 5
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
      volumes:
        - name: data
          persistentVolumeClaim:
            claimName: notifications-db-data
```

Create `k8s/notifications-db/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: notifications-db
  namespace: microwave
spec:
  selector:
    app: notifications-db
  ports:
    - port: 5432
      targetPort: 5432
```

- [ ] **Step 6: Apply and verify all 5 databases come up healthy**

Run: `kubectl apply -R -f k8s/catalog-db -f k8s/orders-db -f k8s/payments-db -f k8s/inventory-db -f k8s/notifications-db`
Run: `kubectl get pods -n microwave -l 'app in (catalog-db,orders-db,payments-db,inventory-db,notifications-db)'`
Expected: after ~30-60s, all 5 Pods show `1/1 Ready` and `STATUS: Running`. Also run `kubectl get pvc -n microwave` and confirm all 5 PVCs show `STATUS: Bound`.

- [ ] **Step 7: Commit**

```bash
git add k8s/catalog-db k8s/orders-db k8s/payments-db k8s/inventory-db k8s/notifications-db
git commit -m "feat(k8s): add Postgres Deployments, Services, Secrets, and PVCs for all 5 databases"
```

---

### Task 3: RabbitMQ

**Files:**
- Create: `k8s/rabbitmq/deployment.yaml`, `k8s/rabbitmq/service.yaml`, `k8s/rabbitmq/service-management.yaml`

**Interfaces:**
- Consumes: Task 1's `microwave` namespace.
- Produces: a `ClusterIP` Service `rabbitmq` on port `5672` (used by `orders`/`payments`/`inventory` in later tasks via `SPRING_RABBITMQ_HOST=rabbitmq`), and a `NodePort` Service `rabbitmq-management` on port `15672` / `nodePort 30672` (mapped to host `15672` by Task 1's `kind-config.yaml`).

- [ ] **Step 1: Write the manifests**

Create `k8s/rabbitmq/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: rabbitmq
  namespace: microwave
  labels:
    app: rabbitmq
spec:
  replicas: 1
  selector:
    matchLabels:
      app: rabbitmq
  template:
    metadata:
      labels:
        app: rabbitmq
    spec:
      containers:
        - name: rabbitmq
          image: rabbitmq:4-management-alpine
          ports:
            - containerPort: 5672
            - containerPort: 15672
          readinessProbe:
            exec:
              command: ["rabbitmq-diagnostics", "-q", "ping"]
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 10
            failureThreshold: 10
```

Create `k8s/rabbitmq/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: rabbitmq
  namespace: microwave
spec:
  selector:
    app: rabbitmq
  ports:
    - port: 5672
      targetPort: 5672
```

Create `k8s/rabbitmq/service-management.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: rabbitmq-management
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: rabbitmq
  ports:
    - port: 15672
      targetPort: 15672
      nodePort: 30672
```

- [ ] **Step 2: Apply and verify**

Run: `kubectl apply -R -f k8s/rabbitmq`
Run: `kubectl get pods -n microwave -l app=rabbitmq`
Expected: `1/1 Ready`, `Running`, after ~30-60s.
Run: `curl -sf -u guest:guest http://localhost:15672/api/overview`
Expected: JSON output (proves the `NodePort` → `kind-config.yaml` host-port mapping works end to end).

- [ ] **Step 3: Commit**

```bash
git add k8s/rabbitmq
git commit -m "feat(k8s): add RabbitMQ Deployment and Services"
```

---

### Task 4: Kafka

**Files:**
- Create: `k8s/kafka/deployment.yaml`, `k8s/kafka/service.yaml`

**Interfaces:**
- Consumes: Task 1's `microwave` namespace.
- Produces: a `ClusterIP` Service `kafka` on port `9092`, used by `orders`/`notifications` in later tasks via `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092`.

- [ ] **Step 1: Write the manifests**

Create `k8s/kafka/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: kafka
  namespace: microwave
  labels:
    app: kafka
spec:
  replicas: 1
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      containers:
        - name: kafka
          image: confluentinc/cp-kafka:7.7.1
          ports:
            - containerPort: 9092
            - containerPort: 9093
          env:
            - name: KAFKA_NODE_ID
              value: "1"
            - name: KAFKA_PROCESS_ROLES
              value: broker,controller
            - name: KAFKA_LISTENERS
              value: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
            - name: KAFKA_ADVERTISED_LISTENERS
              value: PLAINTEXT://kafka:9092
            - name: KAFKA_LISTENER_SECURITY_PROTOCOL_MAP
              value: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
            - name: KAFKA_CONTROLLER_LISTENER_NAMES
              value: CONTROLLER
            - name: KAFKA_CONTROLLER_QUORUM_VOTERS
              value: 1@kafka:9093
            - name: KAFKA_INTER_BROKER_LISTENER_NAME
              value: PLAINTEXT
            - name: KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR
              value: "1"
            - name: CLUSTER_ID
              value: MicrowavePhase3KRaftCluster
          readinessProbe:
            exec:
              command: ["kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 10
            failureThreshold: 10
```

Create `k8s/kafka/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: kafka
  namespace: microwave
spec:
  selector:
    app: kafka
  ports:
    - port: 9092
      targetPort: 9092
```

- [ ] **Step 2: Apply and verify**

Run: `kubectl apply -R -f k8s/kafka`
Run: `kubectl get pods -n microwave -l app=kafka`
Expected: `1/1 Ready`, `Running`, after ~30-60s (Kafka's KRaft startup is slower than Postgres/RabbitMQ — if it's not ready yet, re-check after another 30s before troubleshooting).

- [ ] **Step 3: Commit**

```bash
git add k8s/kafka
git commit -m "feat(k8s): add Kafka Deployment and Service"
```

---

### Task 5: `catalog` — Deployment, Service, ConfigMap, Secret

**Files:**
- Create: `k8s/catalog/configmap.yaml`, `k8s/catalog/secret.yaml`, `k8s/catalog/deployment.yaml`, `k8s/catalog/service.yaml`

**Interfaces:**
- Consumes: Task 2's `catalog-db` Service (`catalog-db:5432`).
- Produces: a `NodePort` Service `catalog` on port `8081` / `nodePort 30081` (mapped to host `8081` by Task 1's `kind-config.yaml`) — consumed by Task 9's `orders` manifests via `CATALOG_SERVICE_URL=http://catalog:8081`.

- [ ] **Step 1: Write the manifests**

Create `k8s/catalog/configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: catalog-config
  namespace: microwave
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://catalog-db:5432/catalog_db
```

Create `k8s/catalog/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: catalog-credentials
  namespace: microwave
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: catalog
  SPRING_DATASOURCE_PASSWORD: catalog
```

Create `k8s/catalog/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: catalog
  namespace: microwave
  labels:
    app: catalog
spec:
  replicas: 2
  selector:
    matchLabels:
      app: catalog
  template:
    metadata:
      labels:
        app: catalog
    spec:
      containers:
        - name: catalog
          image: catalog:kind
          imagePullPolicy: Never
          ports:
            - containerPort: 8081
          envFrom:
            - configMapRef:
                name: catalog-config
            - secretRef:
                name: catalog-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8081
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
```

Create `k8s/catalog/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: catalog
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: catalog
  ports:
    - port: 8081
      targetPort: 8081
      nodePort: 30081
```

- [ ] **Step 2: Build and load the `catalog` image**

Run:
```bash
podman build -t catalog:kind services/catalog
podman save -o /tmp/catalog.tar catalog:kind
kind load image-archive /tmp/catalog.tar --name microwave
```
Expected: each command completes with no error; the last line of `kind load` mentions the image being loaded into the node.

- [ ] **Step 3: Apply and verify**

Run: `kubectl apply -R -f k8s/catalog`
Run: `kubectl get pods -n microwave -l app=catalog`
Expected: 2 Pods, both `1/1 Ready`, `Running`, after ~30-60s. A Pod may show `RESTARTS: 1` or `2` if it started before `catalog-db` was ready — that's expected (no `depends_on`-equivalent exists in K8s; the Pod is restarted until its dependency is reachable), not a defect, as long as it settles into `Running`/`Ready`.
Run: `curl -sf http://localhost:8081/actuator/health`
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add k8s/catalog
git commit -m "feat(k8s): add catalog Deployment, Service, ConfigMap, and Secret"
```

---

### Task 6: `payments` — Deployment, Service, ConfigMap, Secret

**Files:**
- Create: `k8s/payments/configmap.yaml`, `k8s/payments/secret.yaml`, `k8s/payments/deployment.yaml`, `k8s/payments/service.yaml`

**Interfaces:**
- Consumes: Task 2's `payments-db` Service (`payments-db:5432`), Task 3's `rabbitmq` Service (`rabbitmq:5672`).
- Produces: a `NodePort` Service `payments` on port `8082` / `nodePort 30082` (mapped to host `8082`).

- [ ] **Step 1: Write the manifests**

Create `k8s/payments/configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: payments-config
  namespace: microwave
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://payments-db:5432/payments_db
  SPRING_RABBITMQ_HOST: rabbitmq
  SPRING_RABBITMQ_PORT: "5672"
```

Create `k8s/payments/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: payments-credentials
  namespace: microwave
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: payments
  SPRING_DATASOURCE_PASSWORD: payments
```

Create `k8s/payments/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: payments
  namespace: microwave
  labels:
    app: payments
spec:
  replicas: 2
  selector:
    matchLabels:
      app: payments
  template:
    metadata:
      labels:
        app: payments
    spec:
      containers:
        - name: payments
          image: payments:kind
          imagePullPolicy: Never
          ports:
            - containerPort: 8082
          envFrom:
            - configMapRef:
                name: payments-config
            - secretRef:
                name: payments-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8082
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
```

Create `k8s/payments/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: payments
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: payments
  ports:
    - port: 8082
      targetPort: 8082
      nodePort: 30082
```

- [ ] **Step 2: Build and load the `payments` image**

Run:
```bash
podman build -t payments:kind services/payments
podman save -o /tmp/payments.tar payments:kind
kind load image-archive /tmp/payments.tar --name microwave
```

- [ ] **Step 3: Apply and verify**

Run: `kubectl apply -R -f k8s/payments`
Run: `kubectl get pods -n microwave -l app=payments`
Expected: 2 Pods, `1/1 Ready`, `Running`, after ~30-60s.
Run: `curl -sf http://localhost:8082/actuator/health`
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add k8s/payments
git commit -m "feat(k8s): add payments Deployment, Service, ConfigMap, and Secret"
```

---

### Task 7: `inventory` — Deployment, Service, ConfigMap, Secret

**Files:**
- Create: `k8s/inventory/configmap.yaml`, `k8s/inventory/secret.yaml`, `k8s/inventory/deployment.yaml`, `k8s/inventory/service.yaml`

**Interfaces:**
- Consumes: Task 2's `inventory-db` Service (`inventory-db:5432`), Task 3's `rabbitmq` Service (`rabbitmq:5672`).
- Produces: a `NodePort` Service `inventory` on port `8084` / `nodePort 30084` (mapped to host `8084`).

- [ ] **Step 1: Write the manifests**

Create `k8s/inventory/configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: inventory-config
  namespace: microwave
data:
  SPRING_PROFILES_ACTIVE: demo
  SPRING_DATASOURCE_URL: jdbc:postgresql://inventory-db:5432/inventory_db
  SPRING_RABBITMQ_HOST: rabbitmq
  SPRING_RABBITMQ_PORT: "5672"
```

Create `k8s/inventory/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: inventory-credentials
  namespace: microwave
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: inventory
  SPRING_DATASOURCE_PASSWORD: inventory
```

Create `k8s/inventory/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory
  namespace: microwave
  labels:
    app: inventory
spec:
  replicas: 2
  selector:
    matchLabels:
      app: inventory
  template:
    metadata:
      labels:
        app: inventory
    spec:
      containers:
        - name: inventory
          image: inventory:kind
          imagePullPolicy: Never
          ports:
            - containerPort: 8084
          envFrom:
            - configMapRef:
                name: inventory-config
            - secretRef:
                name: inventory-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8084
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8084
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
```

Create `k8s/inventory/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: inventory
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: inventory
  ports:
    - port: 8084
      targetPort: 8084
      nodePort: 30084
```

- [ ] **Step 2: Build and load the `inventory` image**

Run:
```bash
podman build -t inventory:kind services/inventory
podman save -o /tmp/inventory.tar inventory:kind
kind load image-archive /tmp/inventory.tar --name microwave
```

- [ ] **Step 3: Apply and verify**

Run: `kubectl apply -R -f k8s/inventory`
Run: `kubectl get pods -n microwave -l app=inventory`
Expected: 2 Pods, `1/1 Ready`, `Running`, after ~30-60s.
Run: `curl -sf http://localhost:8084/actuator/health`
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add k8s/inventory
git commit -m "feat(k8s): add inventory Deployment, Service, ConfigMap, and Secret"
```

---

### Task 8: `notifications` — Deployment, Service, ConfigMap, Secret

**Files:**
- Create: `k8s/notifications/configmap.yaml`, `k8s/notifications/secret.yaml`, `k8s/notifications/deployment.yaml`, `k8s/notifications/service.yaml`

**Interfaces:**
- Consumes: Task 2's `notifications-db` Service (`notifications-db:5432`), Task 4's `kafka` Service (`kafka:9092`).
- Produces: a `NodePort` Service `notifications` on port `8085` / `nodePort 30085` (mapped to host `8085`).

- [ ] **Step 1: Write the manifests**

Create `k8s/notifications/configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: notifications-config
  namespace: microwave
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://notifications-db:5432/notifications_db
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

Create `k8s/notifications/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notifications-credentials
  namespace: microwave
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: notifications
  SPRING_DATASOURCE_PASSWORD: notifications
```

Create `k8s/notifications/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: notifications
  namespace: microwave
  labels:
    app: notifications
spec:
  replicas: 2
  selector:
    matchLabels:
      app: notifications
  template:
    metadata:
      labels:
        app: notifications
    spec:
      containers:
        - name: notifications
          image: notifications:kind
          imagePullPolicy: Never
          ports:
            - containerPort: 8085
          envFrom:
            - configMapRef:
                name: notifications-config
            - secretRef:
                name: notifications-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8085
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8085
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
```

Create `k8s/notifications/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: notifications
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: notifications
  ports:
    - port: 8085
      targetPort: 8085
      nodePort: 30085
```

- [ ] **Step 2: Build and load the `notifications` image**

Run:
```bash
podman build -t notifications:kind services/notifications
podman save -o /tmp/notifications.tar notifications:kind
kind load image-archive /tmp/notifications.tar --name microwave
```

- [ ] **Step 3: Apply and verify**

Run: `kubectl apply -R -f k8s/notifications`
Run: `kubectl get pods -n microwave -l app=notifications`
Expected: 2 Pods, `1/1 Ready`, `Running`, after ~30-60s.
Run: `curl -sf http://localhost:8085/actuator/health`
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add k8s/notifications
git commit -m "feat(k8s): add notifications Deployment, Service, ConfigMap, and Secret"
```

---

### Task 9: `orders` — Deployment, Service, ConfigMap, Secret

**Files:**
- Create: `k8s/orders/configmap.yaml`, `k8s/orders/secret.yaml`, `k8s/orders/deployment.yaml`, `k8s/orders/service.yaml`

**Interfaces:**
- Consumes: Task 2's `orders-db` Service, Task 3's `rabbitmq` Service, Task 4's `kafka` Service, Task 5's `catalog` Service (`catalog:8081`).
- Produces: a `NodePort` Service `orders` on port `8083` / `nodePort 30083` (mapped to host `8083`).

- [ ] **Step 1: Write the manifests**

Create `k8s/orders/configmap.yaml`:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: orders-config
  namespace: microwave
data:
  SPRING_DATASOURCE_URL: jdbc:postgresql://orders-db:5432/orders_db
  CATALOG_SERVICE_URL: http://catalog:8081
  SPRING_RABBITMQ_HOST: rabbitmq
  SPRING_RABBITMQ_PORT: "5672"
  SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

Create `k8s/orders/secret.yaml`:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: orders-credentials
  namespace: microwave
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: orders
  SPRING_DATASOURCE_PASSWORD: orders
```

Create `k8s/orders/deployment.yaml`:
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: orders
  namespace: microwave
  labels:
    app: orders
spec:
  replicas: 2
  selector:
    matchLabels:
      app: orders
  template:
    metadata:
      labels:
        app: orders
    spec:
      containers:
        - name: orders
          image: orders:kind
          imagePullPolicy: Never
          ports:
            - containerPort: 8083
          envFrom:
            - configMapRef:
                name: orders-config
            - secretRef:
                name: orders-credentials
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8083
            initialDelaySeconds: 30
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8083
            initialDelaySeconds: 30
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
```

Create `k8s/orders/service.yaml`:
```yaml
apiVersion: v1
kind: Service
metadata:
  name: orders
  namespace: microwave
spec:
  type: NodePort
  selector:
    app: orders
  ports:
    - port: 8083
      targetPort: 8083
      nodePort: 30083
```

- [ ] **Step 2: Build and load the `orders` image**

Run:
```bash
podman build -t orders:kind services/orders
podman save -o /tmp/orders.tar orders:kind
kind load image-archive /tmp/orders.tar --name microwave
```

- [ ] **Step 3: Apply and verify**

Run: `kubectl apply -R -f k8s/orders`
Run: `kubectl get pods -n microwave -l app=orders`
Expected: 2 Pods, `1/1 Ready`, `Running`, after ~30-60s.
Run: `curl -sf http://localhost:8083/actuator/health`
Expected: `{"status":"UP"}`.

- [ ] **Step 4: Commit**

```bash
git add k8s/orders
git commit -m "feat(k8s): add orders Deployment, Service, ConfigMap, and Secret"
```

---

### Task 10: Full end-to-end verification against the Kind cluster

**Files:**
- None (verification only — if a defect is found, fix it in the relevant task's manifests and re-run this task).

**Interfaces:**
- Consumes: every Service produced by Tasks 1-9, all reachable at the same host ports `docker-compose.yml` already uses today (8081, 8083, 8084).

- [ ] **Step 1: Confirm every Pod in the namespace is healthy**

Run: `kubectl get pods -n microwave`
Expected: every Pod (12 Deployments — 5 app services ×2 replicas = 10, plus 5 DBs, RabbitMQ, and Kafka = 17 Pods total) shows `Running` and `n/n Ready`.

- [ ] **Step 2: Create a product via `catalog`**

Run:
```bash
curl -s -X POST http://localhost:8081/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Keyboard","description":"Mechanical keyboard","price":350.00}'
```
Expected: HTTP 201 with a JSON body containing `"name":"Keyboard"` and an `"id"` field. Note the `id`.

- [ ] **Step 3: Place an order via `orders`, referencing the product from Step 2**

Run (replace `<id>` with the value from Step 2):
```bash
curl -s -X POST http://localhost:8083/orders \
  -H "Content-Type: application/json" \
  -d '{"productId":<id>,"quantity":1}'
```
Expected: HTTP 201 with `"status":"CREATED"` — this call only persists the order and returns; confirmation happens asynchronously (see `docs/sagas/order-lifecycle.md`). Note the returned order `id`.

- [ ] **Step 4: Poll until the order reaches a final status**

Run (replace `<order-id>`), repeating every few seconds until `status` is no longer `CREATED`:
```bash
curl -s http://localhost:8083/orders/<order-id>
```
Expected: within a few seconds, `"status":"CONFIRMED"` — `350.00` is well under `PaymentSimulator`'s approval threshold (`10000`), so the reservation and payment both succeed. If it stays `CREATED` for more than ~30s, check `kubectl logs -n microwave deploy/orders`, `deploy/inventory`, and `deploy/payments` for errors — most likely a `ConfigMap`/`Secret` value pointing at the wrong Service name.

- [ ] **Step 5: Confirm the RabbitMQ management UI is reachable**

Open `http://localhost:15672` in a browser (or `curl -sf -u guest:guest http://localhost:15672/api/overview`), log in with `guest`/`guest`.
Expected: the UI loads and shows the exchanges/queues declared by `orders`, `inventory`, and `payments` (e.g. `orders.exchange`, `inventory.exchange`, `payments.exchange`).

- [ ] **Step 6: Confirm scaling is real, not just declared**

Run: `kubectl get pods -n microwave -l app=orders -o wide`
Expected: 2 Pods with different `NAME`s and (typically) different `NODE`/`IP` values, both `Ready`. Run `kubectl delete pod -n microwave -l app=orders --field-selector status.phase=Running -o name | head -n1 | xargs kubectl delete -n microwave` (deletes one `orders` Pod) — actually run it as two steps for clarity:
```bash
POD=$(kubectl get pods -n microwave -l app=orders -o jsonpath='{.items[0].metadata.name}')
kubectl delete pod -n microwave "$POD"
kubectl get pods -n microwave -l app=orders
```
Expected: a new `orders` Pod is created automatically to bring the count back to 2 (the declared `replicas: 2`), and `curl -sf http://localhost:8083/actuator/health` keeps working throughout, served by the surviving Pod while the new one starts.

- [ ] **Step 7: Tear down**

Run: `KIND_EXPERIMENTAL_PROVIDER=podman kind delete cluster --name microwave`

No commit for this task — it's verification only, confirming Tasks 1-9 together satisfy the roadmap's Phase 5 "done when" criterion.

---

### Task 11: CI — Kind cluster smoke test

**Files:**
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `k8s/kind-config.yaml`, `k8s/namespace.yaml`, and every manifest from Tasks 2-9; the 5 `Dockerfile`s already present under `services/*/`.
- Produces: a new required-checkable job `k8s-smoke-test` that fails the PR if any Pod doesn't become `Ready` or any app service's `/actuator/health` doesn't respond `200`.

- [ ] **Step 1: Add the job to `.github/workflows/ci.yml`**

In `.github/workflows/ci.yml`, add this job after the existing `docker-build` job and before `sonar`:

```yaml
  k8s-smoke-test:
    name: k8s-smoke-test
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - uses: actions/checkout@v5

      - name: Install kind
        run: |
          curl -Lo /tmp/kind https://kind.sigs.k8s.io/dl/v0.26.0/kind-linux-amd64
          chmod +x /tmp/kind
          sudo mv /tmp/kind /usr/local/bin/kind

      - name: Create kind cluster
        run: kind create cluster --name microwave --config k8s/kind-config.yaml

      - name: Apply namespace
        run: kubectl apply -f k8s/namespace.yaml

      - name: Build and load service images
        run: |
          for svc in catalog orders payments inventory notifications; do
            docker build -t ${svc}:kind services/${svc}
            kind load docker-image ${svc}:kind --name microwave
          done

      - name: Apply manifests
        run: kubectl apply -R -f k8s/

      - name: Wait for all pods to be ready
        run: kubectl wait --for=condition=Ready pods --all -n microwave --timeout=300s

      - name: Check app service health endpoints
        run: |
          for port in 8081 8082 8083 8084 8085; do
            curl -sf http://localhost:${port}/actuator/health
          done

      - name: Dump pod status on failure
        if: failure()
        run: |
          kubectl get pods -n microwave -o wide
          kubectl describe pods -n microwave
```

Note: this job uses `docker` (native on GitHub-hosted runners), not `podman` — the Podman-specific build/load flow (Tasks 5-9's `podman build`/`podman save`/`kind load image-archive`) is only needed for this environment's local Podman-based dev setup. Kind itself also defaults to the Docker provider on the runner, so no `KIND_EXPERIMENTAL_PROVIDER` env var is needed here (unlike Task 1's local `kind create cluster`).

- [ ] **Step 2: Verify the workflow YAML is syntactically valid**

Run: `python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ci.yml'))"`
Expected: no output, no error (confirms valid YAML before pushing).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: add Kind cluster smoke test for K8s manifests"
```

Note: this job's actual pass/fail can only be confirmed once this branch's PR runs on GitHub Actions — local `docker`/`kind` behavior on GitHub-hosted runners can differ subtly from this environment's Podman-based local setup. Treat a failure here as expected-to-debug, not a sign the manifests are wrong; Task 10 already proved the manifests work end-to-end locally.

---

### Task 12: Documentation updates

**Files:**
- Modify: `docs/conventions.md`
- Modify: `docs/decision-log/tech-debts.md`
- Modify: `docs/roadmap.md`
- Modify: `docs/architecture.md`
- Modify: `docs/development-setup.md`

**Interfaces:**
- None — this task only updates living docs to reflect what Tasks 1-11 built, per the project's convention that doc updates land in the same change as the decisions/patterns they describe.

- [ ] **Step 1: Document the K8s manifest conventions in `docs/conventions.md`**

Add a new section to `docs/conventions.md`, after the existing "## Containerization" section:

```markdown
## Kubernetes manifests

Each Deployment+Service unit lives under `k8s/<component>/` (mirroring `services/<service>/`), applied together via `kubectl apply -R -f k8s/`. All objects live in the `microwave` namespace, never `default`. Plain YAML only — no Kustomize, no Helm (see `RA-3`).

Non-sensitive config goes in a `ConfigMap` (`<component>-config`); credentials go in a `Secret` (`<component>-credentials` for app services, `<component>-db-credentials` for databases) — both wired into the container via `envFrom`, mirroring the env-var names `docker-compose.yml` already uses. Env var *values* that point at another component's hostname use the K8s Service name directly (e.g. `SPRING_RABBITMQ_HOST: rabbitmq`) — this is textually identical to the compose container name it replaces, since K8s resolves Service names the same way within a namespace.

App services (`catalog`, `orders`, `payments`, `inventory`, `notifications`) run `replicas: 2` behind a `NodePort` Service, on the same host-facing ports `docker-compose.yml` already publishes. Databases, RabbitMQ, and Kafka run `replicas: 1` — no `StatefulSet`, since none of them cluster — behind a `ClusterIP` Service (RabbitMQ additionally gets a `NodePort` Service for its management UI). A single-replica Postgres Deployment backed by a `PersistentVolumeClaim` uses `strategy: type: Recreate`, since a default `RollingUpdate` would try to mount the same `ReadWriteOnce` volume from two Pods at once.

`docker-compose.yml` stays as a separate, fully maintained option — the K8s manifests don't replace it.
```

- [ ] **Step 2: Resolve `TD-4` and extend `TD-3` in `docs/decision-log/tech-debts.md`**

Move the existing `TD-4` entry from `## Open` to `## Resolved`, changing it to:

```markdown
### TD-4 — DB credentials hardcoded in `docker-compose.yml`

**Introduced in:** Phase 2
**Where:** `docker-compose.yml` — `catalog-db`, `orders-db`, `payments-db`, `inventory-db`, `notifications-db`, and the corresponding `SPRING_DATASOURCE_*` env vars on each service; plus RabbitMQ's `guest`/`guest` credentials, hardcoded in `inventory`'s and `orders`' `application.yml` and left as the default since `docker-compose.yml` sets no RabbitMQ credentials at all

Database usernames/passwords are hardcoded directly in `docker-compose.yml`, at the same security level as the plaintext credentials already present in each service's `application.yml` since Phase 1.

**Why it existed:** these aren't real secrets (local learning-project Postgres credentials), so introducing `.env`-based indirection now would add complexity without reducing any actual risk. See the Phase 2 design spec's rejected-approaches discussion for the full reasoning.

**Resolved in:** Phase 5, via a K8s `Secret` per database (`<component>-db-credentials`) and per app service (`<component>-credentials`), replacing hardcoded credentials for the K8s deployment path.

Note: this resolves the gap for the K8s path only. `docker-compose.yml` and `application.yml` are unchanged and still hold plaintext local credentials — a deliberate choice, not a lingering debt: they're not real secrets, and mirroring the same `Secret`-style indirection there wouldn't reduce any actual risk.
```

In the `TD-3` entry (still under `## Open`), update its "Where" field from:
```markdown
**Where:** `docker-compose.yml` — `catalog`, `orders`, `payments`, `inventory`, `notifications` port mappings, plus RabbitMQ's management UI
```
to:
```markdown
**Where:** `docker-compose.yml` — `catalog`, `orders`, `payments`, `inventory`, `notifications` port mappings, plus RabbitMQ's management UI; and, since Phase 5, the equivalent K8s `NodePort` Services (`k8s/*/service.yaml`, `k8s/rabbitmq/service-management.yaml`) exposing the same ports
```

- [ ] **Step 3: Mark Phase 5 complete in `docs/roadmap.md`**

In `docs/roadmap.md`, update the Phase 5 section — replace:
```markdown
### Phase 5 — Kubernetes orchestration

Migrate from docker-compose to K8s manifests (Deployments, Services, ConfigMaps/Secrets), running locally via Minikube or Kind.
```
with:
```markdown
### Phase 5 — Kubernetes orchestration

**Status:** Complete (2026-08-26). See [`docs/superpowers/specs/2026-08-26-phase5-kubernetes-orchestration-design.md`](superpowers/specs/2026-08-26-phase5-kubernetes-orchestration-design.md) and [`docs/superpowers/plans/2026-08-26-phase5-kubernetes-orchestration.md`](superpowers/plans/2026-08-26-phase5-kubernetes-orchestration.md) for the design and plan it was built from.

Migrate from docker-compose to K8s manifests (Deployments, Services, ConfigMaps/Secrets), running locally via Kind.
```

Update the "## Next step" paragraph at the bottom of `docs/roadmap.md` to note Phase 5 is complete and Phase 6 is next, following the same phrasing pattern already used there for prior phases.

- [ ] **Step 4: Note the Kind cluster in `docs/architecture.md`**

In `docs/architecture.md`, immediately after the "## Current architecture (as of Phase 4)" heading's diagram and bullet list (before "## Target architecture"), add:

```markdown
As of Phase 5, this topology runs on a local Kind cluster (`kubectl apply -R -f k8s/`) rather than directly via `docker-compose` — the diagram's services and edges are unchanged, only how they're deployed. `docker-compose.yml` still works as a separate, fully maintained option.
```

- [ ] **Step 5: Document the Kind workflow in `docs/development-setup.md`**

Add to `docs/development-setup.md`, after the existing "## Running via Docker Compose" section:

```markdown
## Running via Kubernetes (Kind)

Instead of `docker-compose`, the full stack can also run on a local Kind cluster:

1. Install `kind` (v0.26.0) and `kubectl` (matching your cluster's version) — see `k8s/kind-config.yaml` for the cluster shape, and `.github/workflows/ci.yml`'s `k8s-smoke-test` job for the exact install commands used in CI.
2. If using Podman (see the Podman note above), add to `mise.local.toml`:
   ```toml
   [env]
   KIND_EXPERIMENTAL_PROVIDER = "podman"
   ```
3. Create the cluster: `kind create cluster --name microwave --config k8s/kind-config.yaml`
4. Build and load each service's image (repeat per service — `catalog`, `orders`, `payments`, `inventory`, `notifications`):
   ```bash
   podman build -t <service>:kind services/<service>
   podman save -o /tmp/<service>.tar <service>:kind
   kind load image-archive /tmp/<service>.tar --name microwave
   ```
5. Apply the manifests: `kubectl apply -R -f k8s/`
6. Check status: `kubectl get pods -n microwave`

Services are reachable at the same ports as `docker-compose`: `catalog` on `8081`, `payments` on `8082`, `orders` on `8083`, `inventory` on `8084`, `notifications` on `8085`, RabbitMQ's management UI on `15672`.

Tear down: `kind delete cluster --name microwave`.
```

- [ ] **Step 6: Commit**

```bash
git add docs/conventions.md docs/decision-log/tech-debts.md docs/roadmap.md docs/architecture.md docs/development-setup.md
git commit -m "docs: record Phase 5 Kubernetes conventions, tech debt resolution, and roadmap status"
```

---

## Final step: open the PR

After Task 12, push the `phase5-kubernetes-orchestration` branch and open a PR against `main`, following the same flow used for prior phases. Do not push or open the PR without the user's explicit go-ahead in that moment — approval given earlier in this plan doesn't carry over to the push/PR step.
