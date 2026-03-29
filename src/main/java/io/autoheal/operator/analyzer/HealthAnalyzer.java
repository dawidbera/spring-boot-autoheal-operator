package io.autoheal.operator.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Component responsible for analyzing Spring Boot Actuator metrics.
 * Supports memory, thread, and GC analysis.
 */
@Slf4j
@Component
public class HealthAnalyzer {

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Performs a health analysis based on the specified rule type and threshold.
     *
     * @param podIp     The IP address of the pod to analyze.
     * @param type      The type of health check to perform (e.g., "MemoryLeak").
     * @param threshold The threshold value for the check (e.g., "90%").
     * @return An AnalysisResult indicating if the pod is unhealthy and why.
     */
    public AnalysisResult analyze(String podIp, String type, String threshold) {
        return switch (type) {
            case "MemoryLeak" -> analyzeMemory(podIp, threshold);
            case "ThreadDeadlock" -> analyzeThreads(podIp);
            case "GCOverhead" -> analyzeGC(podIp, threshold);
            case "UnresponsiveActuator" -> analyzeUnresponsive(podIp);
            default -> new AnalysisResult(false, "Unknown rule type");
        };
    }

    /**
     * Checks if the actuator health endpoint is responsive.
     *
     * @param podIp The IP address of the pod.
     * @return The result of the responsiveness check.
     */
    private AnalysisResult analyzeUnresponsive(String podIp) {
        try {
            String url = "http://" + podIp + ":8080/actuator/health";
            restTemplate.getForObject(url, String.class);
            return new AnalysisResult(false, "Actuator responsive");
        } catch (Exception e) {
            log.warn("Actuator unresponsive for {}: {}", podIp, e.getMessage());
            return new AnalysisResult(true, "Actuator unresponsive: " + e.getMessage());
        }
    }

    /**
     * Evaluates memory usage relative to the specified percentage threshold.
     *
     * @param podIp     The IP address of the pod.
     * @param threshold The memory usage threshold percentage.
     * @return The result of the memory analysis.
     */
    private AnalysisResult analyzeMemory(String podIp, String threshold) {
        try {
            double used = getMetric(podIp, "jvm.memory.used");
            double max = getMetric(podIp, "jvm.memory.max");
            double usage = (used / max) * 100;
            double limit = Double.parseDouble(threshold.replace("%", ""));

            log.debug("Memory check for {}: {}% (limit: {}%)", podIp, usage, limit);
            if (usage > limit) {
                return new AnalysisResult(true, String.format("Memory usage high: %.2f%%", usage));
            }
        } catch (Exception e) {
            log.warn("Failed to analyze memory for {}: {}", podIp, e.getMessage());
        }
        return new AnalysisResult(false, "Memory OK");
    }

    /**
     * Evaluates thread health by checking for BLOCKED states in the thread dump.
     *
     * @param podIp The IP address of the pod.
     * @return The result of the thread analysis.
     */
    private AnalysisResult analyzeThreads(String podIp) {
        try {
            String url = "http://" + podIp + ":8080/actuator/threaddump";
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            if (root != null && root.has("threads")) {
                for (JsonNode thread : root.get("threads")) {
                    if ("BLOCKED".equals(thread.get("threadState").asText())) {
                        return new AnalysisResult(true, "Detected BLOCKED threads");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to analyze threads for {}: {}", podIp, e.getMessage());
        }
        return new AnalysisResult(false, "Threads OK");
    }

    /**
     * Evaluates GC health based on pause time thresholds.
     *
     * @param podIp     The IP address of the pod.
     * @param threshold The GC pause time threshold.
     * @return The result of the GC analysis.
     */
    private AnalysisResult analyzeGC(String podIp, String threshold) {
        try {
            double gcTime = getMetric(podIp, "jvm.gc.pause");
            // Simple logic: if GC pause exists, we might flag it based on threshold.
            // In a production app, we would compare this over a moving time window.
            if (gcTime > Double.parseDouble(threshold)) {
                return new AnalysisResult(true, "GC Overhead detected");
            }
        } catch (Exception e) {
            log.warn("Failed to analyze GC for {}: {}", podIp, e.getMessage());
        }
        return new AnalysisResult(false, "GC OK");
    }

    /**
     * Retrieves a specific numeric metric from the Actuator endpoint.
     *
     * @param podIp      The IP address of the pod.
     * @param metricName The name of the metric to retrieve.
     * @return The value of the metric, or 0 if not found or on error.
     */
    private double getMetric(String podIp, String metricName) {
        String url = "http://" + podIp + ":8080/actuator/metrics/" + metricName;
        JsonNode node = restTemplate.getForObject(url, JsonNode.class);
        if (node != null && node.has("measurements")) {
            return node.get("measurements").get(0).get("value").asDouble();
        }
        return 0;
    }

    /**
     * Data record representing the outcome of a health analysis.
     *
     * @param unhealthy Whether the pod was determined to be unhealthy.
     * @param reason    A descriptive reason for the health status.
     */
    public record AnalysisResult(boolean unhealthy, String reason) {}
}
