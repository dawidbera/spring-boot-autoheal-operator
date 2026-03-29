package io.autoheal.operator;

import io.autoheal.operator.analyzer.HealthAnalyzer;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Main application class for the Spring Boot Auto-Heal Operator.
 * Bootstraps the Spring context and the Kubernetes Operator framework.
 */
@SpringBootApplication
public class AutoHealOperatorApplication {

    /**
     * Bootstraps the Spring Boot application and registers the Operator SDK.
     *
     * @param args command line arguments.
     */
    public static void main(String[] args) {
        SpringApplication.run(AutoHealOperatorApplication.class, args);
    }

    /**
     * Beans the Reconciler to be registered with the Operator SDK.
     */
    @Bean
    public AutoHealReconciler autoHealReconciler(KubernetesClient client, HealthAnalyzer analyzer) {
        return new AutoHealReconciler(client, analyzer);
    }
}
