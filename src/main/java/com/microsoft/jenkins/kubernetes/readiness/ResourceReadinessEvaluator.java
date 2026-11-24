/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import io.kubernetes.client.openapi.models.V1ClusterRole;
import io.kubernetes.client.openapi.models.V1ClusterRoleBinding;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1DaemonSet;
import io.kubernetes.client.openapi.models.V1DaemonSetStatus;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentCondition;
import io.kubernetes.client.openapi.models.V1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Role;
import io.kubernetes.client.openapi.models.V1RoleBinding;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetStatus;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Pure logic that evaluates whether the current object of a Kubernetes resource is ready.
 * <p>
 * The evaluator never talks to the API server: it takes the resource object as read from the cluster
 * and returns one of the three {@link ReadinessState states}:
 * <ul>
 *     <li>{@link ReadinessState.Status#READY READY} - the resource has converged to the desired state.</li>
 *     <li>{@link ReadinessState.Status#NOT_READY NOT_READY} - no terminal failure yet, the resource may
 *     still converge; the state carries a human readable reason.</li>
 *     <li>{@link ReadinessState.Status#FAILED FAILED} - the resource has deterministically failed and
 *     waiting longer cannot help; the state carries the failure reason.</li>
 * </ul>
 * <p>
 * Resource types whose readiness cannot be observed from their status, or for which readiness
 * evaluation is deliberately not enabled (for example Service and HorizontalPodAutoscaler, see
 * README.md), are treated as ready as soon as the write call succeeds.
 * <p>
 * Rules for the legacy {@code v1beta1}/{@code v1beta2} workload models live in
 * {@link V1betaResourceReadinessEvaluator}; the rules are documented in README.md.
 */
public class ResourceReadinessEvaluator {

    /**
     * Condition type / reason constants as defined by the Kubernetes API.
     */
    static final String DEPLOYMENT_PROGRESSING = "Progressing";
    static final String CONDITION_FALSE = "False";
    static final String PROGRESS_DEADLINE_EXCEEDED = "ProgressDeadlineExceeded";
    static final String JOB_COMPLETE = "Complete";
    static final String JOB_FAILED = "Failed";
    static final String CONDITION_TRUE = "True";
    static final String POD_READY = "Ready";
    static final String POD_SCHEDULED = "PodScheduled";
    static final String POD_UNSCHEDULABLE = "Unschedulable";
    static final String POD_PHASE_SUCCEEDED = "Succeeded";
    static final String POD_PHASE_PENDING = "Pending";
    static final String NAMESPACE_ACTIVE = "Active";

    public ReadinessState evaluate(Object resource) {
        if (resource == null) {
            // The object has not shown up in the cluster yet (or was deleted underneath us).
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_resourceNotObserved());
        }
        if (resource instanceof V1Deployment) {
            return evaluateDeployment((V1Deployment) resource);
        }
        if (resource instanceof V1StatefulSet) {
            return evaluateStatefulSet((V1StatefulSet) resource);
        }
        if (resource instanceof V1DaemonSet) {
            return evaluateDaemonSet((V1DaemonSet) resource);
        }
        if (resource instanceof V1Job) {
            return evaluateJob((V1Job) resource);
        }
        if (resource instanceof V1Pod) {
            return evaluatePod((V1Pod) resource);
        }
        if (resource instanceof V1Namespace) {
            return evaluateNamespace((V1Namespace) resource);
        }
        if (resource instanceof V1ConfigMap
                || resource instanceof V1Secret
                || resource instanceof V1ServiceAccount
                || resource instanceof V1Role
                || resource instanceof V1RoleBinding
                || resource instanceof V1ClusterRole
                || resource instanceof V1ClusterRoleBinding) {
            // These resources have no readiness notion: a successful write means they are ready.
            return ReadinessState.ready();
        }
        // Service, HorizontalPodAutoscaler and the remaining supported types are deliberately not
        // evaluated: a successful write is treated as readiness. See README.md for the decision.
        return ReadinessState.ready();
    }

    private ReadinessState evaluateDeployment(V1Deployment deployment) {
        V1DeploymentStatus status = deployment.getStatus();
        Long generation = deployment.getMetadata() == null ? null : deployment.getMetadata().getGeneration();
        long observedGeneration = status == null || status.getObservedGeneration() == null
                ? 0L : status.getObservedGeneration();
        if (generation != null && observedGeneration < generation) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_deploymentObservedGenerationLagging(
                    observedGeneration, generation));
        }

        if (status != null) {
            V1DeploymentCondition deadlineExceeded = findDeploymentCondition(status.getConditions(),
                    DEPLOYMENT_PROGRESSING, CONDITION_FALSE, PROGRESS_DEADLINE_EXCEEDED);
            if (deadlineExceeded != null) {
                return ReadinessState.failed(Messages.ResourceReadinessEvaluator_deploymentProgressDeadlineExceeded(
                    StringUtils.defaultString(deadlineExceeded.getMessage(), PROGRESS_DEADLINE_EXCEEDED)));
            }
        }

        long desiredReplicas = desiredReplicas(
                deployment.getSpec() == null ? null : deployment.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        long updatedReplicas = status == null || status.getUpdatedReplicas() == null
                ? 0L : status.getUpdatedReplicas();
        long replicas = status == null || status.getReplicas() == null ? 0L : status.getReplicas();
        long availableReplicas = status == null || status.getAvailableReplicas() == null
                ? 0L : status.getAvailableReplicas();
        if (updatedReplicas != desiredReplicas
                || replicas != desiredReplicas
                || availableReplicas != desiredReplicas) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_deploymentReplicasNotReady(
                    updatedReplicas, replicas, availableReplicas, desiredReplicas));
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateStatefulSet(V1StatefulSet statefulSet) {
        V1StatefulSetStatus status = statefulSet.getStatus();
        Long generation = statefulSet.getMetadata() == null ? null : statefulSet.getMetadata().getGeneration();
        long observedGeneration = status == null || status.getObservedGeneration() == null
                ? 0L : status.getObservedGeneration();
        if (generation != null && observedGeneration < generation) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_statefulSetObservedGenerationLagging(
                    observedGeneration, generation));
        }

        long desiredReplicas = statefulSet.getSpec() == null || statefulSet.getSpec().getReplicas() == null
                ? 1L : statefulSet.getSpec().getReplicas();
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        long readyReplicas = status == null || status.getReadyReplicas() == null
                ? 0L : status.getReadyReplicas();
        long updatedReplicas = status == null || status.getUpdatedReplicas() == null
                ? 0L : status.getUpdatedReplicas();
        String currentRevision = status == null ? null : status.getCurrentRevision();
        String updateRevision = status == null ? null : status.getUpdateRevision();
        boolean revisionRolledOut = updateRevision == null
                || StringUtils.equals(currentRevision, updateRevision);
        if (readyReplicas != desiredReplicas
                || updatedReplicas != desiredReplicas
                || !revisionRolledOut) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_statefulSetReplicasNotReady(
                    readyReplicas, updatedReplicas, desiredReplicas, currentRevision, updateRevision));
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateDaemonSet(V1DaemonSet daemonSet) {
        V1DaemonSetStatus status = daemonSet.getStatus();
        Long generation = daemonSet.getMetadata() == null ? null : daemonSet.getMetadata().getGeneration();
        long observedGeneration = status == null || status.getObservedGeneration() == null
                ? 0L : status.getObservedGeneration();
        if (generation != null && observedGeneration < generation) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_daemonSetObservedGenerationLagging(
                    observedGeneration, generation));
        }

        long desiredNumberScheduled = status == null || status.getDesiredNumberScheduled() == null
                ? 0L : status.getDesiredNumberScheduled();
        if (desiredNumberScheduled == 0) {
            return ReadinessState.ready();
        }
        long updatedNumberScheduled = status == null || status.getUpdatedNumberScheduled() == null
                ? 0L : status.getUpdatedNumberScheduled();
        long numberAvailable = status == null || status.getNumberAvailable() == null
                ? 0L : status.getNumberAvailable();
        if (updatedNumberScheduled != desiredNumberScheduled
                || numberAvailable != desiredNumberScheduled) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_daemonSetReplicasNotReady(
                    updatedNumberScheduled, numberAvailable, desiredNumberScheduled));
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateJob(V1Job job) {
        V1JobStatus status = job.getStatus();
        if (status != null) {
            V1JobCondition failed = findJobCondition(status.getConditions(), JOB_FAILED, CONDITION_TRUE, null);
            if (failed != null) {
                return ReadinessState.failed(formatJobFailureReason(failed));
            }
            V1JobCondition complete = findJobCondition(status.getConditions(), JOB_COMPLETE, CONDITION_TRUE, null);
            if (complete != null) {
                return ReadinessState.ready();
            }
        }
        return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_jobNotComplete());
    }

    private static String formatJobFailureReason(V1JobCondition condition) {
        String reason = StringUtils.defaultString(condition.getReason(), "");
        String message = StringUtils.defaultString(condition.getMessage(), "");
        if (StringUtils.isBlank(message)) {
            return Messages.ResourceReadinessEvaluator_jobFailed(reason);
        }
        return Messages.ResourceReadinessEvaluator_jobFailedWithMessage(reason, message);
    }

    private ReadinessState evaluatePod(V1Pod pod) {
        V1PodStatus status = pod.getStatus();
        if (status == null) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_podNoStatus());
        }
        if (POD_PHASE_SUCCEEDED.equals(status.getPhase())) {
            return ReadinessState.ready();
        }
        V1PodCondition ready = findPodCondition(status.getConditions(), POD_READY, CONDITION_TRUE, null);
        if (ready != null) {
            return ReadinessState.ready();
        }
        if (POD_PHASE_PENDING.equals(status.getPhase())) {
            V1PodCondition unschedulable = findPodCondition(
                    status.getConditions(), POD_SCHEDULED, CONDITION_FALSE, POD_UNSCHEDULABLE);
            if (unschedulable != null) {
                // Surface the scheduler message verbatim so the real cause (e.g. insufficient resources)
                // is shown in the build log.
                return ReadinessState.notReady(StringUtils.defaultString(
                        unschedulable.getMessage(),
                        Messages.ResourceReadinessEvaluator_podUnschedulableWithoutMessage()));
            }
        }
        return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_podNotReady(
                StringUtils.defaultString(status.getPhase(), "")));
    }

    private ReadinessState evaluateNamespace(V1Namespace namespace) {
        if (namespace.getStatus() != null
                && NAMESPACE_ACTIVE.equals(namespace.getStatus().getPhase())) {
            return ReadinessState.ready();
        }
        return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_namespaceNotActive(
                namespace.getStatus() == null ? "" : StringUtils.defaultString(namespace.getStatus().getPhase())));
    }

    private static V1DeploymentCondition findDeploymentCondition(
            List<V1DeploymentCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (V1DeploymentCondition condition : conditions) {
            if (matches(condition.getType(), condition.getStatus(), condition.getReason(), type, status, reason)) {
                return condition;
            }
        }
        return null;
    }

    private static V1JobCondition findJobCondition(
            List<V1JobCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (V1JobCondition condition : conditions) {
            if (matches(condition.getType(), condition.getStatus(), condition.getReason(), type, status, reason)) {
                return condition;
            }
        }
        return null;
    }

    private static V1PodCondition findPodCondition(
            List<V1PodCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (V1PodCondition condition : conditions) {
            if (matches(condition.getType(), condition.getStatus(), condition.getReason(), type, status, reason)) {
                return condition;
            }
        }
        return null;
    }

    private static boolean matches(
            String actualType, String actualStatus, String actualReason,
            String expectedType, String expectedStatus, String expectedReason) {
        return StringUtils.equals(actualType, expectedType)
                && StringUtils.equals(actualStatus, expectedStatus)
                && (expectedReason == null || StringUtils.equals(actualReason, expectedReason));
    }

    /**
     * A Deployment / StatefulSet with {@code spec.replicas} unset behaves as if replicas were 1;
     * an explicit 0 means the workload is scaled down and is considered ready.
     */
    private static long desiredReplicas(Integer specReplicas) {
        return specReplicas == null ? 1L : specReplicas.longValue();
    }
}
