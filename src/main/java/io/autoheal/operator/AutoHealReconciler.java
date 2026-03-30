package io.autoheal.operator;

import io.autoheal.operator.analyzer.HealthAnalyzer;
import io.fabric8.kubernetes.api.model.Secret;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Kubernetes Reconciler for AutoHealPolicy resources.
 * Continuously monitors pods and applies remediation actions defined in the policy.
 */
@Slf4j
@io.javaoperatorsdk.operator.api.reconciler.Reconciler(name = "autohealreconciler")
public class AutoHealReconciler implements Reconciler<AutoHealPolicy> {

    private final KubernetesClient client;
    private final HealthAnalyzer analyzer;
    private final RestTemplate restTemplate = new RestTemplate();
    private final Map<String, Long> lastActionCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Constructs a new AutoHealReconciler with the required dependencies.
     *
     * @param client   The Kubernetes client for managing pods.
     * @param analyzer The health analyzer for evaluating actuator metrics.
     */
    public AutoHealReconciler(KubernetesClient client, HealthAnalyzer analyzer) {
        this.client = client;
        this.analyzer = analyzer;
    }

    /**
     * Main reconciliation loop invoked by the Operator SDK.
     * Identifies pods matching the policy selector and evaluates health rules for each.
     *
     * @param resource the AutoHealPolicy resource being reconciled.
     * @param context the reconciliation context.
     * @return the update control instructing the operator SDK on how to proceed.
     */
    @Override
    public UpdateControl<AutoHealPolicy> reconcile(AutoHealPolicy resource, Context<AutoHealPolicy> context) {
        log.info("Reconciling AutoHealPolicy: {}", resource.getMetadata().getName());

        Map<String, String> selector = resource.getSpec().getSelector();
        List<Pod> pods = client.pods().inNamespace(resource.getMetadata().getNamespace())
                .withLabels(selector)
                .list()
                .getItems();

        HttpHeaders headers = prepareHeaders(resource);

        for (Pod pod : pods) {
            processPod(pod, resource, headers);
        }

        return UpdateControl.noUpdate();
    }

    /**
     * Prepares HTTP headers for Actuator requests, including Basic Auth if configured.
     *
     * @param policy the AutoHealPolicy resource.
     * @return HttpHeaders with auth if applicable.
     */
    private HttpHeaders prepareHeaders(AutoHealPolicy policy) {
        HttpHeaders headers = new HttpHeaders();
        if (policy.getSpec().getAuth() != null && policy.getSpec().getAuth().getSecretName() != null) {
            String secretName = policy.getSpec().getAuth().getSecretName();
            String namespace = policy.getMetadata().getNamespace();
            Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
            
            if (secret != null && secret.getData() != null) {
                String username = decode(secret.getData().get("username"));
                String password = decode(secret.getData().get("password"));
                if (username != null && password != null) {
                    headers.setBasicAuth(username, password);
                }
            } else {
                log.warn("Secret {} not found in namespace {} or missing data.", secretName, namespace);
            }
        }
        return headers;
    }

    private String decode(String base64) {
        return base64 != null ? new String(Base64.getDecoder().decode(base64)) : null;
    }

    /**
     * Processes a single pod: evaluates rules and triggers actions if necessary,
     * considering the cool-down period.
     *
     * @param pod the pod to process.
     * @param policy the policy determining the health rules.
     * @param headers HTTP headers for the analysis.
     */
    private void processPod(Pod pod, AutoHealPolicy policy, HttpHeaders headers) {
        String podName = pod.getMetadata().getName();
        String podIp = pod.getStatus().getPodIP();
        int port = policy.getSpec().getPort();

        if (podIp == null || !"Running".equals(pod.getStatus().getPhase())) return;

        // Check Cool-down mechanism
        if (isCoolingDown(podName, policy.getSpec().getCoolDownSeconds())) {
            log.debug("Pod {} is in cool-down period. Skipping analysis.", podName);
            return;
        }

        for (AutoHealPolicySpec.Rule rule : policy.getSpec().getRules()) {
            HealthAnalyzer.AnalysisResult result = analyzer.analyze(podIp, port, headers, rule.getType(), rule.getThreshold());
            if (result.unhealthy()) {
                log.error("Rule {} triggered for pod {}: {}", rule.getType(), podName, result.reason());
                emitEvent(policy, pod, "AutoHealTriggered", 
                    String.format("Rule %s triggered: %s. Action: %s", rule.getType(), result.reason(), rule.getAction()));
                executeAction(pod, rule.getAction(), port, headers);
                lastActionCache.put(podName, System.currentTimeMillis());
                break;
            }
        }
    }

    /**
     * Emits a Kubernetes event for the given policy and pod.
     *
     * @param policy  The owner policy.
     * @param pod     The target pod.
     * @param reason  Short, machine-readable reason (e.g., "AutoHealTriggered").
     * @param message Human-readable description.
     */
    private void emitEvent(AutoHealPolicy policy, Pod pod, String reason, String message) {
        try {
            client.events().v1().events().inNamespace(policy.getMetadata().getNamespace()).resource(
                new io.fabric8.kubernetes.api.model.events.v1.EventBuilder()
                    .withNewMetadata()
                        .withGenerateName("autoheal-")
                    .endMetadata()
                    .withReason(reason)
                    .withNote(message)
                    .withType("Normal")
                    .withNewRegarding()
                        .withKind(pod.getKind())
                        .withName(pod.getMetadata().getName())
                        .withNamespace(pod.getMetadata().getNamespace())
                        .withUid(pod.getMetadata().getUid())
                    .endRegarding()
                    .withEventTime(new io.fabric8.kubernetes.api.model.MicroTime(java.time.Instant.now().toString()))
                    .withAction("AutoHeal")
                    .build()
            ).create();
        } catch (Exception e) {
            log.warn("Failed to emit Kubernetes event: {}", e.getMessage());
        }
    }

    /**
     * Checks if a pod is currently in the cool-down period after a recent action.
     *
     * @param podName the name of the pod to check.
     * @param coolDownSeconds the duration of the cool-down period in seconds.
     * @return true if the pod is cooling down, false otherwise.
     */
    private boolean isCoolingDown(String podName, int coolDownSeconds) {
        Long lastAction = lastActionCache.get(podName);
        if (lastAction == null) return false;

        long elapsedSeconds = (System.currentTimeMillis() - lastAction) / 1000;
        return elapsedSeconds < coolDownSeconds;
    }

    /**
     * Executes the specified remediation action for a pod.
     * Supported actions: RestartWithDump, Restart, RecreatePod, ScaleUp.
     *
     * @param pod the target pod for the action.
     * @param action the type of action to perform.
     * @param port the actuator port.
     * @param headers HTTP headers for potential dump capture.
     */
    private void executeAction(Pod pod, String action, int port, HttpHeaders headers) {
        String podName = pod.getMetadata().getName();
        String namespace = pod.getMetadata().getNamespace();

        switch (action) {
            case "RestartWithDump" -> {
                captureDump(pod, port, headers);
                restartPod(pod);
            }
            case "Restart", "RecreatePod" -> restartPod(pod);
            case "ScaleUp" -> scaleUpParent(pod);
            default -> log.warn("Unknown action: {}", action);
        }
    }

    /**
     * Scales up the parent Deployment or ReplicaSet of the pod by 1 replica.
     *
     * @param pod the pod whose parent should be scaled.
     */
    private void scaleUpParent(Pod pod) {
        String podName = pod.getMetadata().getName();
        pod.getMetadata().getOwnerReferences().stream()
                .filter(ref -> "ReplicaSet".equals(ref.getKind()))
                .findFirst()
                .ifPresent(rsRef -> {
                    // Find the Deployment that owns this ReplicaSet
                    client.apps().replicaSets().inNamespace(pod.getMetadata().getNamespace())
                            .withName(rsRef.getName()).get().getMetadata().getOwnerReferences().stream()
                            .filter(ref -> "Deployment".equals(ref.getKind()))
                            .findFirst()
                            .ifPresent(depRef -> {
                                log.info("Scaling up Deployment {}/{}", pod.getMetadata().getNamespace(), depRef.getName());
                                client.apps().deployments().inNamespace(pod.getMetadata().getNamespace())
                                        .withName(depRef.getName())
                                        .scale(client.apps().deployments().inNamespace(pod.getMetadata().getNamespace())
                                                .withName(depRef.getName()).get().getSpec().getReplicas() + 1);
                            });
                });
    }

    /**
     * Captures a thread dump from the pod's actuator endpoint for diagnostic purposes.
     *
     * @param pod the pod from which to capture the dump.
     * @param port the actuator port.
     * @param headers HTTP headers for auth.
     */
    private void captureDump(Pod pod, int port, HttpHeaders headers) {
        String podIp = pod.getStatus().getPodIP();
        try {
            String url = String.format("http://%s:%d/actuator/threaddump", podIp, port);
            String dump = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
            log.info("DIAGNOSTIC DUMP for {}:\n{}", pod.getMetadata().getName(), dump);
            // In production, save this to a PersistentVolume or external storage
        } catch (Exception e) {
            log.error("Failed to capture dump for {}: {}", pod.getMetadata().getName(), e.getMessage());
        }
    }

    /**
     * Restarts a pod by deleting it, allowing the ReplicaSet/Deployment to recreate it.
     *
     * @param pod the pod to be restarted.
     */
    private void restartPod(Pod pod) {
        log.info("Restarting pod {}/{}", pod.getMetadata().getNamespace(), pod.getMetadata().getName());
        client.pods().inNamespace(pod.getMetadata().getNamespace())
                .withName(pod.getMetadata().getName())
                .delete();
    }
}
