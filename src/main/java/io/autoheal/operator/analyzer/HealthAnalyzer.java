package io.autoheal.operator.analyzer;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
     * @param port      The port of the actuator endpoint.
     * @param headers   HTTP headers (including authentication if needed).
     * @param type      The type of health check to perform (e.g., "MemoryLeak").
     * @param threshold The threshold value for the check (e.g., "90%").
     * @return An AnalysisResult indicating if the pod is unhealthy and why.
     */
    public AnalysisResult analyze(String podIp, int port, HttpHeaders headers, String type, String threshold) {
        return switch (type) {
            case "MemoryLeak" -> analyzeMemory(podIp, port, headers, threshold);
            case "ThreadDeadlock" -> analyzeThreads(podIp, port, headers);
            case "GCOverhead" -> analyzeGC(podIp, port, headers, threshold);
            case "UnresponsiveActuator" -> analyzeUnresponsive(podIp, port, headers);
            default -> new AnalysisResult(false, "Unknown rule type");
        };
    }

    /**
     * Checks if the actuator health endpoint is responsive.
     *
     * @param podIp   The IP address of the pod.
     * @param port    The port of the actuator endpoint.
     * @param headers HTTP headers.
     * @return The result of the responsiveness check.
     */
    private AnalysisResult analyzeUnresponsive(String podIp, int port, HttpHeaders headers) {
        try {
            String url = String.format("http://%s:%d/actuator/health", podIp, port);
            restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
     * @param port      The port of the actuator endpoint.
     * @param headers   HTTP headers.
     * @param threshold The memory usage threshold percentage.
     * @return The result of the memory analysis.
     */
    private AnalysisResult analyzeMemory(String podIp, int port, HttpHeaders headers, String threshold) {
        try {
            double used = getMetric(podIp, port, headers, "jvm.memory.used");
            double max = getMetric(podIp, port, headers, "jvm.memory.max");
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
     * @param podIp   The IP address of the pod.
     * @param port    The port of the actuator endpoint.
     * @param headers HTTP headers.
     * @return The result of the thread analysis.
     */
    private AnalysisResult analyzeThreads(String podIp, int port, HttpHeaders headers) {
        try {
            String url = String.format("http://%s:%d/actuator/threaddump", podIp, port);
            JsonNode root = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
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
     * @param port      The port of the actuator endpoint.
     * @param headers   HTTP headers.
     * @param threshold The GC pause time threshold.
     * @return The result of the GC analysis.
     */
    private AnalysisResult analyzeGC(String podIp, int port, HttpHeaders headers, String threshold) {
        try {
            double gcTime = getMetric(podIp, port, headers, "jvm.gc.pause");
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
     * @param port       The port of the actuator endpoint.
     * @param headers    HTTP headers.
     * @param metricName The name of the metric to retrieve.
     * @return The value of the metric, or 0 if not found or on error.
     */
    private double getMetric(String podIp, int port, HttpHeaders headers, String metricName) {
        String url = String.format("http://%s:%d/actuator/metrics/%s", podIp, port, metricName);
        JsonNode node = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class).getBody();
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
