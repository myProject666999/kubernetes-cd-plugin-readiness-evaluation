/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1DaemonSet;
import io.kubernetes.client.openapi.models.V1DaemonSetStatus;
import io.kubernetes.client.openapi.models.V1Deployment;
import io.kubernetes.client.openapi.models.V1DeploymentBuilder;
import io.kubernetes.client.openapi.models.V1DeploymentCondition;
import io.kubernetes.client.openapi.models.V1DeploymentStatus;
import io.kubernetes.client.openapi.models.V1Job;
import io.kubernetes.client.openapi.models.V1JobCondition;
import io.kubernetes.client.openapi.models.V1JobStatus;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodCondition;
import io.kubernetes.client.openapi.models.V1PodStatus;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.openapi.models.V1StatefulSet;
import io.kubernetes.client.openapi.models.V1StatefulSetStatus;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the pure v1 {@link ResourceReadinessEvaluator}.
 */
public class ResourceReadinessEvaluatorTest {

    private ResourceReadinessEvaluator evaluator;

    @Before
    public void setUp() {
        evaluator = new ResourceReadinessEvaluator();
    }

    private static V1ObjectMeta metadata(long generation) {
        return new V1ObjectMeta().name("web").namespace("default").generation(generation);
    }

    private static V1DeploymentCondition condition(String type, String status, String reason, String message) {
        return new V1DeploymentCondition().type(type).status(status).reason(reason).message(message);
    }

    // ---- Deployment ----

    @Test
    public void testDeploymentReadyWhenAllReplicasMatch() {
        V1Deployment deployment = new V1DeploymentBuilder()
                .withMetadata(metadata(2L))
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withReplicas(3).withUpdatedReplicas(3).withAvailableReplicas(3)
                .endStatus()
                .build();

        assertTrue(evaluator.evaluate(deployment).isReady());
    }

    @Test
    public void testDeploymentNotReadyWhenAvailableReplicasLagging() {
        V1Deployment deployment = new V1DeploymentBuilder()
                .withMetadata(metadata(2L))
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withReplicas(3).withUpdatedReplicas(3).withAvailableReplicas(1)
                .endStatus()
                .build();

        ReadinessState state = evaluator.evaluate(deployment);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        // The reason carries the concrete replica counts.
        assertTrue(state.getReason().contains("updated 3"));
        assertTrue(state.getReason().contains("available 1"));
        assertTrue(state.getReason().contains("3 desired"));
    }

    @Test
    public void testDeploymentFailedOnProgressDeadlineExceeded() {
        V1DeploymentStatus status = new V1DeploymentStatus()
                .observedGeneration(2L)
                .replicas(3).updatedReplicas(3).availableReplicas(1)
                .addConditionsItem(condition(
                        "Progressing", "False", "ProgressDeadlineExceeded",
                        "Deployment has exceeded its progress deadline."));
        V1Deployment deployment = new V1Deployment()
                .metadata(metadata(2L))
                .spec(new io.kubernetes.client.openapi.models.V1DeploymentSpec().replicas(3))
                .status(status);

        ReadinessState state = evaluator.evaluate(deployment);
        assertEquals(ReadinessState.Status.FAILED, state.getStatus());
        assertTrue(state.getReason().contains("ProgressDeadlineExceeded"));
        assertTrue(state.getReason().contains("Deployment has exceeded its progress deadline."));
    }

    @Test
    public void testDeploymentNotReadyWhenObservedGenerationBehind() {
        V1Deployment deployment = new V1DeploymentBuilder()
                .withMetadata(metadata(3L))
                .withNewSpec().withReplicas(3).endSpec()
                .withNewStatus()
                .withObservedGeneration(2L)
                .withReplicas(3).withUpdatedReplicas(3).withAvailableReplicas(3)
                .endStatus()
                .build();

        ReadinessState state = evaluator.evaluate(deployment);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertTrue(state.getReason().contains("2"));
        assertTrue(state.getReason().contains("3"));
    }

    @Test
    public void testDeploymentWithUnsetReplicasTreatedAsOne() {
        // spec.replicas is null: desired replicas default to 1.
        V1Deployment notThereYet = new V1DeploymentBuilder()
                .withMetadata(metadata(1L))
                .withNewSpec().endSpec()
                .withNewStatus()
                .withObservedGeneration(1L)
                .withReplicas(0).withUpdatedReplicas(0).withAvailableReplicas(0)
                .endStatus()
                .build();
        ReadinessState notReady = evaluator.evaluate(notThereYet);
        assertEquals(ReadinessState.Status.NOT_READY, notReady.getStatus());

        V1Deployment ready = new V1DeploymentBuilder()
                .withMetadata(metadata(1L))
                .withNewSpec().endSpec()
                .withNewStatus()
                .withObservedGeneration(1L)
                .withReplicas(1).withUpdatedReplicas(1).withAvailableReplicas(1)
                .endStatus()
                .build();
        assertTrue(evaluator.evaluate(ready).isReady());
    }

    @Test
    public void testDeploymentScaledToZeroIsReady() {
        V1Deployment deployment = new V1DeploymentBuilder()
                .withMetadata(metadata(1L))
                .withNewSpec().withReplicas(0).endSpec()
                .withNewStatus().withObservedGeneration(1L).endStatus()
                .build();

        assertTrue(evaluator.evaluate(deployment).isReady());
    }

    // ---- StatefulSet ----

    @Test
    public void testStatefulSetReady() {
        V1StatefulSet statefulSet = new V1StatefulSet()
                .metadata(metadata(1L))
                .spec(new io.kubernetes.client.openapi.models.V1StatefulSetSpec().replicas(2))
                .status(new V1StatefulSetStatus()
                        .observedGeneration(1L)
                        .readyReplicas(2).updatedReplicas(2)
                        .currentRevision("rev-2").updateRevision("rev-2"));

        assertTrue(evaluator.evaluate(statefulSet).isReady());
    }

    @Test
    public void testStatefulSetNotReadyWhenRevisionRollingOut() {
        V1StatefulSet statefulSet = new V1StatefulSet()
                .metadata(metadata(1L))
                .spec(new io.kubernetes.client.openapi.models.V1StatefulSetSpec().replicas(2))
                .status(new V1StatefulSetStatus()
                        .observedGeneration(1L)
                        .readyReplicas(1).updatedReplicas(1)
                        .currentRevision("rev-1").updateRevision("rev-2"));

        ReadinessState state = evaluator.evaluate(statefulSet);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertTrue(state.getReason().contains("rev-1"));
        assertTrue(state.getReason().contains("rev-2"));
    }

    // ---- DaemonSet ----

    @Test
    public void testDaemonSetReady() {
        V1DaemonSet daemonSet = new V1DaemonSet()
                .metadata(metadata(1L))
                .status(new V1DaemonSetStatus()
                        .observedGeneration(1L)
                        .desiredNumberScheduled(4)
                        .updatedNumberScheduled(4)
                        .numberAvailable(4));

        assertTrue(evaluator.evaluate(daemonSet).isReady());
    }

    @Test
    public void testDaemonSetNotReadyWhenUpdatedNumberLagging() {
        V1DaemonSet daemonSet = new V1DaemonSet()
                .metadata(metadata(1L))
                .status(new V1DaemonSetStatus()
                        .observedGeneration(1L)
                        .desiredNumberScheduled(4)
                        .updatedNumberScheduled(2)
                        .numberAvailable(4));

        ReadinessState state = evaluator.evaluate(daemonSet);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertTrue(state.getReason().contains("updated 2"));
        assertTrue(state.getReason().contains("4 desired"));
    }

    // ---- Job ----

    @Test
    public void testJobReadyWhenComplete() {
        V1Job job = new V1Job().status(new V1JobStatus()
                .addConditionsItem(new V1JobCondition().type("Complete").status("True")));

        assertTrue(evaluator.evaluate(job).isReady());
    }

    @Test
    public void testJobFailsWithReasonAndMessage() {
        V1Job job = new V1Job().status(new V1JobStatus()
                .addConditionsItem(new V1JobCondition()
                        .type("Failed").status("True")
                        .reason("BackoffLimitExceeded")
                        .message("Job has reached the specified backoff limit")));

        ReadinessState state = evaluator.evaluate(job);
        assertEquals(ReadinessState.Status.FAILED, state.getStatus());
        assertTrue(state.getReason().startsWith("BackoffLimitExceeded"));
        assertTrue(state.getReason().contains("Job has reached the specified backoff limit"));
    }

    @Test
    public void testJobNotReadyBeforeCompletion() {
        V1Job job = new V1Job().status(new V1JobStatus().active(1));

        ReadinessState state = evaluator.evaluate(job);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
    }

    // ---- Pod ----

    @Test
    public void testPodReadyWhenReadyConditionTrue() {
        V1Pod pod = new V1Pod().status(new V1PodStatus()
                .phase("Running")
                .addConditionsItem(new V1PodCondition().type("Ready").status("True")));

        assertTrue(evaluator.evaluate(pod).isReady());
    }

    @Test
    public void testPodReadyWhenSucceeded() {
        V1Pod pod = new V1Pod().status(new V1PodStatus().phase("Succeeded"));

        assertTrue(evaluator.evaluate(pod).isReady());
    }

    @Test
    public void testPendingUnschedulablePodSurfacesSchedulerMessageVerbatim() {
        String schedulerMessage = "0/3 nodes are available: 3 Insufficient cpu.";
        V1Pod pod = new V1Pod().status(new V1PodStatus()
                .phase("Pending")
                .addConditionsItem(new V1PodCondition()
                        .type("PodScheduled").status("False")
                        .reason("Unschedulable").message(schedulerMessage)));

        ReadinessState state = evaluator.evaluate(pod);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertEquals(schedulerMessage, state.getReason());
    }

    @Test
    public void testPendingScheduledPodIsSimplyNotReady() {
        V1Pod pod = new V1Pod().status(new V1PodStatus().phase("Pending"));

        ReadinessState state = evaluator.evaluate(pod);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertFalse(state.isFailed());
    }

    // ---- Namespace ----

    @Test
    public void testNamespaceActiveIsReady() {
        V1Namespace namespace = new V1Namespace()
                .metadata(new V1ObjectMeta().name("team-a"))
                .status(new io.kubernetes.client.openapi.models.V1NamespaceStatus().phase("Active"));

        assertTrue(evaluator.evaluate(namespace).isReady());
    }

    @Test
    public void testNamespaceTerminatingIsNotReady() {
        V1Namespace namespace = new V1Namespace()
                .metadata(new V1ObjectMeta().name("team-a"))
                .status(new io.kubernetes.client.openapi.models.V1NamespaceStatus().phase("Terminating"));

        assertEquals(ReadinessState.Status.NOT_READY, evaluator.evaluate(namespace).getStatus());
    }

    // ---- Write-only resources ----

    @Test
    public void testConfigMapSecretAndRbacAreReadyOnWrite() {
        assertTrue(evaluator.evaluate(new V1ConfigMap()).isReady());
        assertTrue(evaluator.evaluate(new V1Secret()).isReady());
        assertTrue(evaluator.evaluate(new io.kubernetes.client.openapi.models.V1ServiceAccount()).isReady());
        assertTrue(evaluator.evaluate(new io.kubernetes.client.openapi.models.V1Role()).isReady());
        assertTrue(evaluator.evaluate(new io.kubernetes.client.openapi.models.V1RoleBinding()).isReady());
        assertTrue(evaluator.evaluate(new io.kubernetes.client.openapi.models.V1ClusterRole()).isReady());
        assertTrue(evaluator.evaluate(new io.kubernetes.client.openapi.models.V1ClusterRoleBinding()).isReady());
    }

    @Test
    public void testServiceIsNotEvaluated() {
        // Deliberately not part of the readiness rules.
        assertTrue(evaluator.evaluate(new V1Service()).isReady());
    }

    @Test
    public void testNullResourceIsNotReady() {
        ReadinessState state = evaluator.evaluate(null);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertFalse(state.isFailed());
    }

    @Test
    public void testReadyStateHasNoReason() {
        assertNull(evaluator.evaluate(new V1ConfigMap()).getReason());
        assertEquals(ReadinessState.Status.READY, ReadinessState.ready().getStatus());
    }
}
