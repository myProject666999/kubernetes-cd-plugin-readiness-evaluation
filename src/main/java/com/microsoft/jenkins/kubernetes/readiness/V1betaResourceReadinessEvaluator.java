/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import io.kubernetes.client.openapi.models.AppsV1beta1Deployment;
import io.kubernetes.client.openapi.models.AppsV1beta1DeploymentCondition;
import io.kubernetes.client.openapi.models.AppsV1beta1DeploymentStatus;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Deployment;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1DeploymentCondition;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1beta1DaemonSet;
import io.kubernetes.client.openapi.models.V1beta1DaemonSetStatus;
import io.kubernetes.client.openapi.models.V1beta1StatefulSet;
import io.kubernetes.client.openapi.models.V1beta1StatefulSetStatus;
import io.kubernetes.client.openapi.models.V1beta2DaemonSet;
import io.kubernetes.client.openapi.models.V1beta2DaemonSetStatus;
import io.kubernetes.client.openapi.models.V1beta2Deployment;
import io.kubernetes.client.openapi.models.V1beta2DeploymentCondition;
import io.kubernetes.client.openapi.models.V1beta2DeploymentStatus;
import io.kubernetes.client.openapi.models.V1beta2StatefulSet;
import io.kubernetes.client.openapi.models.V1beta2StatefulSetStatus;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Readiness evaluation for the legacy {@code extensions/v1beta1}, {@code apps/v1beta1} and
 * {@code apps/v1beta2} workload models.
 * <p>
 * The beta model classes were inspected before reusing the v1 rules. They expose the same status
 * fields as their v1 counterparts, so the same rule set applies:
 * <ul>
 *     <li><b>Deployment</b> ({@code ExtensionsV1beta1Deployment}, {@code AppsV1beta1Deployment},
 *     {@code V1beta2Deployment}): {@code status.observedGeneration &gt;= metadata.generation},
 *     {@code updatedReplicas == replicas == availableReplicas == spec.replicas} (unset replicas
 *     means 1, 0 means ready), and a {@code Progressing=False} condition with reason
 *     {@code ProgressDeadlineExceeded} is a terminal failure. All three beta status classes carry
 *     {@code observedGeneration}, {@code updatedReplicas}, {@code replicas}, {@code availableReplicas}
 *     and a conditions list with the same {@code type/status/reason/message} shape.</li>
 *     <li><b>StatefulSet</b> ({@code V1beta1StatefulSet}, {@code V1beta2StatefulSet}): both status
 *     classes carry {@code readyReplicas}, {@code updatedReplicas}, {@code currentRevision} and
 *     {@code updateRevision}, so the v1 rule (replicas plus revision equality) applies verbatim.</li>
 *     <li><b>DaemonSet</b> ({@code V1beta1DaemonSet}, {@code V1beta2DaemonSet}): both status classes
 *     carry {@code updatedNumberScheduled}, {@code numberAvailable} and {@code desiredNumberScheduled},
 *     so the v1 rule applies verbatim.</li>
 * </ul>
 * Legacy ReplicaSet, CronJob and Ingress models do not have a v1 counterpart covered by the
 * readiness rules, and their statuses do not expose an equivalent terminal-failure condition.
 * They keep the historical behavior: a successful write is treated as readiness. The same applies
 * to Service and HorizontalPodAutoscaler across all versions, which are deliberately not evaluated
 * (see README.md).
 */
public class V1betaResourceReadinessEvaluator {

    private static final long DEFAULT_REPLICAS = 1L;

    public ReadinessState evaluate(Object resource) {
        if (resource == null) {
            return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_resourceNotObserved());
        }
        if (resource instanceof V1beta2Deployment) {
            return evaluateV1beta2Deployment((V1beta2Deployment) resource);
        }
        if (resource instanceof ExtensionsV1beta1Deployment) {
            return evaluateExtensionsDeployment((ExtensionsV1beta1Deployment) resource);
        }
        if (resource instanceof AppsV1beta1Deployment) {
            return evaluateAppsV1beta1Deployment((AppsV1beta1Deployment) resource);
        }
        if (resource instanceof V1beta2StatefulSet) {
            return evaluateV1beta2StatefulSet((V1beta2StatefulSet) resource);
        }
        if (resource instanceof V1beta1StatefulSet) {
            return evaluateV1beta1StatefulSet((V1beta1StatefulSet) resource);
        }
        if (resource instanceof V1beta2DaemonSet) {
            return evaluateV1beta2DaemonSet((V1beta2DaemonSet) resource);
        }
        if (resource instanceof V1beta1DaemonSet) {
            return evaluateV1beta1DaemonSet((V1beta1DaemonSet) resource);
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateV1beta2Deployment(V1beta2Deployment deployment) {
        V1beta2DeploymentStatus status = deployment.getStatus();
        Long generation = deployment.getMetadata() == null ? null : deployment.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        if (status != null) {
            V1beta2DeploymentCondition deadlineExceeded = findV1beta2DeploymentCondition(status.getConditions(),
                    ResourceReadinessEvaluator.DEPLOYMENT_PROGRESSING,
                    ResourceReadinessEvaluator.CONDITION_FALSE,
                    ResourceReadinessEvaluator.PROGRESS_DEADLINE_EXCEEDED);
            if (deadlineExceeded != null) {
                return progressDeadlineExceeded(deadlineExceeded.getMessage());
            }
        }
        long desiredReplicas = desiredReplicas(
                deployment.getSpec() == null ? null : deployment.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        return deploymentReplicas(
                intOrZero(status == null ? null : status.getUpdatedReplicas()),
                intOrZero(status == null ? null : status.getReplicas()),
                intOrZero(status == null ? null : status.getAvailableReplicas()),
                desiredReplicas);
    }

    private ReadinessState evaluateExtensionsDeployment(ExtensionsV1beta1Deployment deployment) {
        ExtensionsV1beta1DeploymentStatus status = deployment.getStatus();
        Long generation = deployment.getMetadata() == null ? null : deployment.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        if (status != null) {
            ExtensionsV1beta1DeploymentCondition deadlineExceeded =
                    findExtensionsDeploymentCondition(status.getConditions(),
                    ResourceReadinessEvaluator.DEPLOYMENT_PROGRESSING,
                    ResourceReadinessEvaluator.CONDITION_FALSE,
                    ResourceReadinessEvaluator.PROGRESS_DEADLINE_EXCEEDED);
            if (deadlineExceeded != null) {
                return progressDeadlineExceeded(deadlineExceeded.getMessage());
            }
        }
        long desiredReplicas = desiredReplicas(
                deployment.getSpec() == null ? null : deployment.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        return deploymentReplicas(
                intOrZero(status == null ? null : status.getUpdatedReplicas()),
                intOrZero(status == null ? null : status.getReplicas()),
                intOrZero(status == null ? null : status.getAvailableReplicas()),
                desiredReplicas);
    }

    private ReadinessState evaluateAppsV1beta1Deployment(AppsV1beta1Deployment deployment) {
        AppsV1beta1DeploymentStatus status = deployment.getStatus();
        Long generation = deployment.getMetadata() == null ? null : deployment.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        if (status != null) {
            AppsV1beta1DeploymentCondition deadlineExceeded = findAppsV1beta1DeploymentCondition(
                    status.getConditions(),
                    ResourceReadinessEvaluator.DEPLOYMENT_PROGRESSING,
                    ResourceReadinessEvaluator.CONDITION_FALSE,
                    ResourceReadinessEvaluator.PROGRESS_DEADLINE_EXCEEDED);
            if (deadlineExceeded != null) {
                return progressDeadlineExceeded(deadlineExceeded.getMessage());
            }
        }
        long desiredReplicas = desiredReplicas(
                deployment.getSpec() == null ? null : deployment.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        return deploymentReplicas(
                intOrZero(status == null ? null : status.getUpdatedReplicas()),
                intOrZero(status == null ? null : status.getReplicas()),
                intOrZero(status == null ? null : status.getAvailableReplicas()),
                desiredReplicas);
    }

    private ReadinessState evaluateV1beta2StatefulSet(V1beta2StatefulSet statefulSet) {
        V1beta2StatefulSetStatus status = statefulSet.getStatus();
        Long generation = statefulSet.getMetadata() == null ? null : statefulSet.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        long desiredReplicas = desiredReplicas(
                statefulSet.getSpec() == null ? null : statefulSet.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        long readyReplicas = intOrZero(status == null ? null : status.getReadyReplicas());
        long updatedReplicas = intOrZero(status == null ? null : status.getUpdatedReplicas());
        String currentRevision = status == null ? null : status.getCurrentRevision();
        String updateRevision = status == null ? null : status.getUpdateRevision();
        boolean revisionRolledOut = updateRevision == null
                || StringUtils.equals(currentRevision, updateRevision);
        if (readyReplicas != desiredReplicas || updatedReplicas != desiredReplicas || !revisionRolledOut) {
            return ReadinessState.notReady(Messages.V1betaResourceReadinessEvaluator_betaStatefulSetRevisionMismatch(
                    readyReplicas, updatedReplicas, desiredReplicas,
                    StringUtils.defaultString(currentRevision), StringUtils.defaultString(updateRevision)));
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateV1beta1StatefulSet(V1beta1StatefulSet statefulSet) {
        V1beta1StatefulSetStatus status = statefulSet.getStatus();
        Long generation = statefulSet.getMetadata() == null ? null : statefulSet.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        long desiredReplicas = desiredReplicas(
                statefulSet.getSpec() == null ? null : statefulSet.getSpec().getReplicas());
        if (desiredReplicas == 0) {
            return ReadinessState.ready();
        }
        long readyReplicas = intOrZero(status == null ? null : status.getReadyReplicas());
        long updatedReplicas = intOrZero(status == null ? null : status.getUpdatedReplicas());
        String currentRevision = status == null ? null : status.getCurrentRevision();
        String updateRevision = status == null ? null : status.getUpdateRevision();
        boolean revisionRolledOut = updateRevision == null
                || StringUtils.equals(currentRevision, updateRevision);
        if (readyReplicas != desiredReplicas || updatedReplicas != desiredReplicas || !revisionRolledOut) {
            return ReadinessState.notReady(Messages.V1betaResourceReadinessEvaluator_betaStatefulSetRevisionMismatch(
                    readyReplicas, updatedReplicas, desiredReplicas,
                    StringUtils.defaultString(currentRevision), StringUtils.defaultString(updateRevision)));
        }
        return ReadinessState.ready();
    }

    private ReadinessState evaluateV1beta2DaemonSet(V1beta2DaemonSet daemonSet) {
        V1beta2DaemonSetStatus status = daemonSet.getStatus();
        Long generation = daemonSet.getMetadata() == null ? null : daemonSet.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        long desiredNumberScheduled = intOrZero(status == null ? null : status.getDesiredNumberScheduled());
        if (desiredNumberScheduled == 0) {
            return ReadinessState.ready();
        }
        return daemonSetReplicas(
                intOrZero(status == null ? null : status.getUpdatedNumberScheduled()),
                intOrZero(status == null ? null : status.getNumberAvailable()),
                desiredNumberScheduled);
    }

    private ReadinessState evaluateV1beta1DaemonSet(V1beta1DaemonSet daemonSet) {
        V1beta1DaemonSetStatus status = daemonSet.getStatus();
        Long generation = daemonSet.getMetadata() == null ? null : daemonSet.getMetadata().getGeneration();
        if (isObservedGenerationLagging(generation, status == null ? null : status.getObservedGeneration())) {
            return observedGenerationLagging(status == null ? 0L : status.getObservedGeneration(), generation);
        }
        long desiredNumberScheduled = intOrZero(status == null ? null : status.getDesiredNumberScheduled());
        if (desiredNumberScheduled == 0) {
            return ReadinessState.ready();
        }
        return daemonSetReplicas(
                intOrZero(status == null ? null : status.getUpdatedNumberScheduled()),
                intOrZero(status == null ? null : status.getNumberAvailable()),
                desiredNumberScheduled);
    }

    private static boolean isObservedGenerationLagging(Long generation, Long observedGeneration) {
        return generation != null && (observedGeneration == null || observedGeneration < generation);
    }

    private static ReadinessState observedGenerationLagging(long observedGeneration, Long generation) {
        return ReadinessState.notReady(Messages.ResourceReadinessEvaluator_deploymentObservedGenerationLagging(
                observedGeneration, generation));
    }

    private static ReadinessState progressDeadlineExceeded(String message) {
        return ReadinessState.failed(Messages.ResourceReadinessEvaluator_deploymentProgressDeadlineExceeded(
                StringUtils.defaultString(message, ResourceReadinessEvaluator.PROGRESS_DEADLINE_EXCEEDED)));
    }

    private static ReadinessState deploymentReplicas(
            long updatedReplicas, long replicas, long availableReplicas, long desiredReplicas) {
        if (updatedReplicas == desiredReplicas
                && replicas == desiredReplicas
                && availableReplicas == desiredReplicas) {
            return ReadinessState.ready();
        }
        return ReadinessState.notReady(Messages.V1betaResourceReadinessEvaluator_betaDeploymentReplicasNotReady(
                updatedReplicas, replicas, availableReplicas, desiredReplicas));
    }

    private static ReadinessState daemonSetReplicas(
            long updatedNumberScheduled, long numberAvailable, long desiredNumberScheduled) {
        if (updatedNumberScheduled == desiredNumberScheduled && numberAvailable == desiredNumberScheduled) {
            return ReadinessState.ready();
        }
        return ReadinessState.notReady(Messages.V1betaResourceReadinessEvaluator_betaDaemonSetReplicasNotReady(
                updatedNumberScheduled, numberAvailable, desiredNumberScheduled));
    }

    private static long desiredReplicas(Integer specReplicas) {
        return specReplicas == null ? DEFAULT_REPLICAS : specReplicas.longValue();
    }

    private static long intOrZero(Integer value) {
        return value == null ? 0L : value.longValue();
    }

    private static V1beta2DeploymentCondition findV1beta2DeploymentCondition(
            List<V1beta2DeploymentCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (V1beta2DeploymentCondition condition : conditions) {
            if (matches(condition.getType(), condition.getStatus(), condition.getReason(), type, status, reason)) {
                return condition;
            }
        }
        return null;
    }

    private static ExtensionsV1beta1DeploymentCondition findExtensionsDeploymentCondition(
            List<ExtensionsV1beta1DeploymentCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (ExtensionsV1beta1DeploymentCondition condition : conditions) {
            if (matches(condition.getType(), condition.getStatus(), condition.getReason(), type, status, reason)) {
                return condition;
            }
        }
        return null;
    }

    private static AppsV1beta1DeploymentCondition findAppsV1beta1DeploymentCondition(
            List<AppsV1beta1DeploymentCondition> conditions, String type, String status, String reason) {
        if (conditions == null) {
            return null;
        }
        for (AppsV1beta1DeploymentCondition condition : conditions) {
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
}
