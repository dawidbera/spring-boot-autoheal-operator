# Spring Boot Auto-Heal Operator

🚀 **A Kubernetes-native controller designed for intelligent self-healing of Spring Boot microservices.**

The **Spring Boot Auto-Heal Operator** bridges the gap between application-level metrics (Spring Boot Actuator) and infrastructure-level management (Kubernetes). It detects and resolves runtime failures—such as memory leaks, thread deadlocks, and GC thrashing—that standard Kubernetes liveness probes often miss.

## ✨ Features

- **Application-Aware Health Checks:** Monitors deep JVM metrics via Spring Boot Actuator.
- **Predictive Remediation:** Detects issues like memory leak patterns before the process crashes.
- **Diagnostic Dumps:** Automatically captures Thread Dumps before restarting a failing Pod for post-mortem analysis.
- **Cool-down Mechanism:** Prevents "restart storms" using a configurable `coolDownSeconds` period.
- **CRD-Driven:** Configure healing policies per application using simple Kubernetes manifests.
- **Automated CI/CD:** Integrated GitHub Actions pipeline for testing, YAML validation, and Docker image publishing (GHCR).

## 🛠 Tech Stack

- **Java 21**
- **Spring Boot 3.2.4**
- **Java Operator SDK** (for Kubernetes controller logic)
- **Fabric8 Kubernetes Client**
- **GitHub Actions** (CI/CD Pipeline)
- **K3s** (Target environment)

## 🏗 Architecture & Request Flow

The **Spring Boot Auto-Heal Operator** acts as a centralized "brain" for your microservices ecosystem, monitoring health and performance through a continuous reconciliation loop.

### Architecture Overview
```mermaid
graph TD
    subgraph "DevOps Lifecycle (GitHub)"
        Code[Java Source Code] --> CI[GH Actions: Build & Test]
        CI --> CD[GH Actions: Push to GHCR]
    end

    subgraph "Kubernetes Infrastructure (K3s)"
        CD -->|Pull Image| OpPod[Auto-Heal Operator Pod]
        API[K8s API Server] <-->|Watch/Notify| OpPod
        CRD[Custom Resource: AutoHealPolicy] -->|Defines Rules| API
        
        subgraph "Microservices Ecosystem"
            MS1[Service A: Payment]
            MS2[Service B: Inventory]
            MS3[Service C: Order]
        end
        
        OpPod -- 1. Analyze (Actuator) --> MS1
        OpPod -- 2. Decision (Analyzer) --> OpPod
        OpPod -- 3. Action (Delete/Scale) --> API
        API -- 4. Remediation --> MS1
        OpPod -- 5. Log Event --> API
    end
```

### Request Flow (Self-Healing Loop)
```mermaid
sequenceDiagram
    participant K8s as K8s API Server
    participant Op as Auto-Heal Operator
    participant App as Target Microservice
    
    Note over Op: Reconciliation Loop (Interval)
    Op->>K8s: 1. Watch & List Pods (Selector)
    K8s-->>Op: [pod-1, pod-2]
    
    loop for each Pod
        Op->>Op: Check Cool-down Period
        Op->>App: 2. GET /actuator/health / metrics
        App-->>Op: 200 OK (Status: UP, Memory: 95%)
        
        alt Rule Triggered (e.g. MemoryLeak)
            Op->>App: 3. GET /actuator/threaddump
            App-->>Op: (Thread Dump JSON)
            Note over Op: Log Diagnostic Data
            Op->>K8s: 4. Create Kubernetes Event
            Op->>K8s: 5. Delete Pod (Restart)
            K8s->>App: SIGTERM
            Note over Op: Start Cool-down Timer
        else Healthy
            Note over Op: Continue Monitoring
        end
    end
```

1.  **AutoHealPolicy (CRD):** Defines which Pods to monitor and which rules to apply.
2.  **HealthAnalyzer:** Queries Actuator endpoints (`/actuator/health`, `/actuator/metrics`, `/actuator/threaddump`) to assess JVM health.
3.  **Operator Reconciler:** Executes remediation actions (Restart, RestartWithDump, etc.) based on analysis results.

## 🚀 Quick Start

### 1. Prerequisites
- A running Kubernetes cluster (e.g., K3s, Minikube).
- `kubectl` configured to your cluster.
- Java 21+ installed.

### 2. Install the Custom Resource Definition (CRD)
```bash
kubectl apply -f k8s/crd.yaml
```

### 3. Run the Operator
Locally (using your `kubeconfig`):
```bash
mvn spring-boot:run
```

### 4. Apply a Healing Policy
```yaml
apiVersion: autoheal.io/v1
kind: AutoHealPolicy
metadata:
  name: spring-app-policy
spec:
  coolDownSeconds: 300
  selector:
    app: my-service
  rules:
    - type: MemoryLeak
      threshold: "90%"
      action: RestartWithDump
    - type: ThreadDeadlock
      action: Restart
```
Apply it:
```bash
kubectl apply -f k8s/example-policy.yaml
```

## 🧪 Testing
For a detailed guide on how to simulate failures and verify the operator's behavior, see [TESTING.md](./TESTING.md).

