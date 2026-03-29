# Auto-Heal Operator Testing Guide

### Step 1: Environment Preparation
Apply the CRD and run the operator locally (or in the cluster):
```bash
kubectl apply -f k8s/crd.yaml
# In a separate terminal:
mvn spring-boot:run
```

### Step 2: Deploy Mock Application and Policy
```bash
kubectl apply -f k8s/test-mock-app.yaml
kubectl apply -f k8s/example-policy.yaml
```

### Step 3: Scenario 1 - Actuator Failure (Health DOWN)
1. Check pods: `kubectl get pods -l app=my-spring-app`
2. Trigger failure:
   ```bash
   POD_NAME=$(kubectl get pod -l app=my-spring-app -o jsonpath='{.items[0].metadata.name}')
   kubectl exec $POD_NAME -- curl -s http://localhost:8080/fail
   ```
3. Observe operator logs. You should see: `Pod ... is unhealthy! Triggering restart...`
4. Verify the pod was restarted: `kubectl get pods -l app=my-spring-app -w`

### Step 4: Scenario 2 - Memory Leak
1. Modify the policy in `k8s/example-policy.yaml` to include the `MemoryLeak` rule:
   ```yaml
   rules:
     - type: MemoryLeak
       threshold: "90%"
       action: RestartWithDump
   ```
2. Apply changes: `kubectl apply -f k8s/example-policy.yaml`
3. Simulate a leak:
   ```bash
   POD_NAME=$(kubectl get pod -l app=my-spring-app -o jsonpath='{.items[0].metadata.name}')
   kubectl exec $POD_NAME -- curl -s http://localhost:8080/leak
   ```
4. The operator will detect memory usage exceeding 90% (simulated at 950MB/1GB) and perform a restart with a diagnostic dump in the logs.

### Step 5: Cool-down Verification
If you try to trigger a failure again immediately after a restart, the operator will ignore it for the first 5 minutes (as per `coolDownSeconds`).
