package io.autoheal.operator;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Specification for the AutoHealPolicy.
 * Contains selectors to identify target pods and a list of rules for health monitoring.
 */
@Data
public class AutoHealPolicySpec {
    /** Label selector to identify target pods managed by this policy. */
    private Map<String, String> selector;
    
    /** List of rules to be evaluated for each pod. */
    private List<Rule> rules;
    
    /** Minimum time in seconds between remediation actions for a single pod. */
    private int coolDownSeconds = 300; // Default: 5 minutes

    /**
     * Defines a single monitoring rule and its corresponding remediation action.
     */
    @Data
    public static class Rule {
        /**
         * The type of health check to perform.
         * Possible values: MemoryLeak, ThreadDeadlock, GCOverhead.
         */
        private String type;
        
        /**
         * The threshold for triggering the rule (e.g., "90%" for MemoryLeak).
         * Threshold format depends on the rule type.
         */
        private String threshold;
        
        /**
         * The action to perform if the rule is triggered.
         * Possible values: Restart, RestartWithDump, ScaleUp.
         */
        private String action;
    }
}
