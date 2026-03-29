package io.autoheal.operator;

import io.autoheal.operator.analyzer.HealthAnalyzer;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the AutoHealReconciler.
 * Uses a mock Kubernetes client and an analyzer to verify remediation logic.
 */
@EnableKubernetesMockClient(crud = true)
class AutoHealReconcilerTest {

    private KubernetesClient client;
    private HealthAnalyzer analyzer;
    private AutoHealReconciler reconciler;

    /**
     * Initializes the test environment with a mock analyzer and the reconciler.
     */
    @BeforeEach
    void setUp() {
        analyzer = mock(HealthAnalyzer.class);
        reconciler = new AutoHealReconciler(client, analyzer);
        // Clear all pods before each test to ensure isolation
        client.pods().inAnyNamespace().delete();
    }

    /**
     * Verifies that no remediation action is taken when the analyzer reports the pod is healthy.
     */
    @Test
    void testReconcile_HealthyPod_NoAction() {
        // Given
        String podIp = "10.0.0.1";
        AutoHealPolicy policy = createPolicy("test-policy", "app", "my-app");
        createPod("my-pod", "app", "my-app", podIp, "Running");
        
        when(analyzer.analyze(eq(podIp), anyString(), anyString()))
                .thenReturn(new HealthAnalyzer.AnalysisResult(false, "OK"));

        // When
        UpdateControl<AutoHealPolicy> result = reconciler.reconcile(policy, null);

        // Then
        assertNotNull(result);
        assertNotNull(client.pods().inNamespace("default").withName("my-pod").get(), "Pod should not be deleted when healthy");
    }

    /**
     * Verifies that a pod is restarted (deleted) when the analyzer reports the pod is unhealthy.
     */
    @Test
    void testReconcile_UnhealthyPod_TriggersRestart() {
        // Given
        String podIp = "10.0.0.2";
        AutoHealPolicy policy = createPolicy("restart-policy", "app", "bad-app");
        createPod("bad-pod", "app", "bad-app", podIp, "Running");
        
        when(analyzer.analyze(eq(podIp), anyString(), anyString()))
                .thenReturn(new HealthAnalyzer.AnalysisResult(true, "Memory Leak Detected"));

        // When
        reconciler.reconcile(policy, null);

        // Then
        assertNull(client.pods().inNamespace("default").withName("bad-pod").get(), "Pod should be deleted when unhealthy");
    }

    /**
     * Helper method to create a sample AutoHealPolicy for testing.
     *
     * @param name       The name of the policy.
     * @param labelKey   The label key to select pods.
     * @param labelValue The label value to select pods.
     * @return A configured AutoHealPolicy instance.
     */
    private AutoHealPolicy createPolicy(String name, String labelKey, String labelValue) {
        AutoHealPolicy policy = new AutoHealPolicy();
        policy.getMetadata().setName(name);
        policy.getMetadata().setNamespace("default");
        
        AutoHealPolicySpec spec = new AutoHealPolicySpec();
        spec.setSelector(Map.of(labelKey, labelValue));
        spec.setCoolDownSeconds(0); 
        
        AutoHealPolicySpec.Rule rule = new AutoHealPolicySpec.Rule();
        rule.setType("MemoryLeak");
        rule.setThreshold("90%");
        rule.setAction("Restart");
        spec.setRules(Collections.singletonList(rule));
        
        policy.setSpec(spec);
        return policy;
    }

    /**
     * Helper method to create a sample Pod in the mock Kubernetes client.
     *
     * @param name       The name of the pod.
     * @param labelKey   The label key for selection.
     * @param labelValue The label value for selection.
     * @param ip         The pod's IP address.
     * @param phase      The current phase of the pod (e.g., "Running").
     */
    private void createPod(String name, String labelKey, String labelValue, String ip, String phase) {
        Pod pod = new PodBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace("default")
                    .addToLabels(labelKey, labelValue)
                .endMetadata()
                .withNewStatus()
                    .withPodIP(ip)
                    .withPhase(phase)
                .endStatus()
                .build();
        client.pods().inNamespace("default").resource(pod).create();
    }
}
