package io.autoheal.operator;

import lombok.Data;

/**
 * Status information for an AutoHealPolicy.
 * Tracks the outcome of the reconciliation process.
 */
@Data
public class AutoHealPolicyStatus {
    /**
     * ISO-8601 timestamp of the last successful health check performed by the operator.
     */
    private String lastCheck;
    
    /**
     * General status summary of the managed pods (e.g., "AllHealthy", "Remediating").
     */
    private String status;
    
    /**
     * Cumulative total number of restarts triggered by this specific policy across all pods.
     */
    private int restartCount;
}
