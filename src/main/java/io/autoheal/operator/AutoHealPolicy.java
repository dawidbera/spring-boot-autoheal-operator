package io.autoheal.operator;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Version;
import io.fabric8.kubernetes.model.annotation.ShortNames;

/**
 * Custom Resource Definition representing an Auto-Heal Policy.
 * This resource defines how the operator should monitor and remediate specific sets of Pods.
 */
@Group("autoheal.io")
@Version("v1")
@ShortNames("ahp")
public class AutoHealPolicy extends CustomResource<AutoHealPolicySpec, AutoHealPolicyStatus> implements Namespaced {
}
