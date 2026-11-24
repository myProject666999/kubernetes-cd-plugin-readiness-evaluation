/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import io.kubernetes.client.openapi.models.AppsV1beta1Deployment;
import io.kubernetes.client.openapi.models.ExtensionsV1beta1Deployment;
import io.kubernetes.client.openapi.models.V1beta1DaemonSet;
import io.kubernetes.client.openapi.models.V1beta1StatefulSet;
import io.kubernetes.client.openapi.models.V1beta2DaemonSet;
import io.kubernetes.client.openapi.models.V1beta2Deployment;
import io.kubernetes.client.openapi.models.V1beta2StatefulSet;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link V1betaResourceReadinessEvaluator}. The beta models expose the same status
 * fields as v1, so these tests verify that the v1 rules are reused model for model.
 */
public class V1betaResourceReadinessEvaluatorTest {

    private V1betaResourceReadinessEvaluator evaluator;

    @Before
    public void setUp() {
        evaluator = new V1betaResourceReadinessEvaluator();
    }

    @Test
    public void v1beta2DeploymentReusesTheV1Rules() {
        V1beta2Deployment notReady = new V1beta2Deployment()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.V1beta2DeploymentSpec().replicas(3))
                .status(new io.kubernetes.client.openapi.models.V1beta2DeploymentStatus()
                        .observedGeneration(1L)
                        .updatedReplicas(3).replicas(3).availableReplicas(1));
        ReadinessState state = evaluator.evaluate(notReady);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertTrue(state.getReason().contains("updated 3"));
        assertTrue(state.getReason().contains("available 1"));

        V1beta2Deployment ready = new V1beta2Deployment()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.V1beta2DeploymentSpec().replicas(3))
                .status(new io.kubernetes.client.openapi.models.V1beta2DeploymentStatus()
                        .observedGeneration(1L)
                        .updatedReplicas(3).replicas(3).availableReplicas(3));
        assertTrue(evaluator.evaluate(ready).isReady());
    }

    @Test
    public void v1beta2DeploymentProgressDeadlineExceededIsAFailure() {
        V1beta2Deployment deployment = new V1beta2Deployment()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.V1beta2DeploymentSpec().replicas(1))
                .status(new io.kubernetes.client.openapi.models.V1beta2DeploymentStatus()
                        .observedGeneration(1L)
                        .addConditionsItem(new io.kubernetes.client.openapi.models.V1beta2DeploymentCondition()
                                .type("Progressing").status("False")
                                .reason("ProgressDeadlineExceeded").message("timed out")));
        ReadinessState state = evaluator.evaluate(deployment);
        assertEquals(ReadinessState.Status.FAILED, state.getStatus());
        assertTrue(state.getReason().contains("ProgressDeadlineExceeded"));
    }

    @Test
    public void extensionsAndAppsV1beta1DeploymentReuseTheV1Rules() {
        ExtensionsV1beta1Deployment extensions = new ExtensionsV1beta1Deployment()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.ExtensionsV1beta1DeploymentSpec().replicas(2))
                .status(new io.kubernetes.client.openapi.models.ExtensionsV1beta1DeploymentStatus()
                        .observedGeneration(1L)
                        .updatedReplicas(2).replicas(2).availableReplicas(2));
        assertTrue(evaluator.evaluate(extensions).isReady());

        AppsV1beta1Deployment apps = new AppsV1beta1Deployment()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.AppsV1beta1DeploymentSpec().replicas(2))
                .status(new io.kubernetes.client.openapi.models.AppsV1beta1DeploymentStatus()
                        .observedGeneration(0L)
                        .updatedReplicas(2).replicas(2).availableReplicas(2));
        ReadinessState state = evaluator.evaluate(apps);
        assertEquals(ReadinessState.Status.NOT_READY, state.getStatus());
        assertTrue(state.getReason().contains("behind"));
    }

    @Test
    public void statefulSetsCheckReplicasAndRevisionsInBothBetaVersions() {
        V1beta2StatefulSet v1beta2 = new V1beta2StatefulSet()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.V1beta2StatefulSetSpec().replicas(2))
                .status(new io.kubernetes.client.openapi.models.V1beta2StatefulSetStatus()
                        .observedGeneration(1L)
                        .readyReplicas(1).updatedReplicas(1)
                        .currentRevision("rev-1").updateRevision("rev-2"));
        ReadinessState v1beta2State = evaluator.evaluate(v1beta2);
        assertEquals(ReadinessState.Status.NOT_READY, v1beta2State.getStatus());

        V1beta1StatefulSet v1beta1 = new V1beta1StatefulSet()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .spec(new io.kubernetes.client.openapi.models.V1beta1StatefulSetSpec().replicas(2))
                .status(new io.kubernetes.client.openapi.models.V1beta1StatefulSetStatus()
                        .observedGeneration(1L)
                        .readyReplicas(2).updatedReplicas(2)
                        .currentRevision("rev-2").updateRevision("rev-2"));
        assertTrue(evaluator.evaluate(v1beta1).isReady());
    }

    @Test
    public void daemonSetsCheckScheduledAndAvailableInBothBetaVersions() {
        V1beta2DaemonSet v1beta2 = new V1beta2DaemonSet()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .status(new io.kubernetes.client.openapi.models.V1beta2DaemonSetStatus()
                        .observedGeneration(1L)
                        .desiredNumberScheduled(4)
                        .updatedNumberScheduled(4).numberAvailable(3));
        assertEquals(ReadinessState.Status.NOT_READY, evaluator.evaluate(v1beta2).getStatus());

        V1beta1DaemonSet v1beta1 = new V1beta1DaemonSet()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta().generation(1L))
                .status(new io.kubernetes.client.openapi.models.V1beta1DaemonSetStatus()
                        .observedGeneration(1L)
                        .desiredNumberScheduled(4)
                        .updatedNumberScheduled(4).numberAvailable(4));
        assertTrue(evaluator.evaluate(v1beta1).isReady());
    }

    @Test
    public void nullResourceIsNotReadyAndUnknownTypesKeepWriteReadyBehavior() {
        assertEquals(ReadinessState.Status.NOT_READY, evaluator.evaluate(null).getStatus());
        // Legacy ReplicaSet / Ingress / CronJob keep the historical behavior.
        assertTrue(evaluator.evaluate(
                new io.kubernetes.client.openapi.models.V1beta1ReplicaSet()).isReady());
        assertTrue(evaluator.evaluate(
                new io.kubernetes.client.openapi.models.V1beta1CronJob()).isReady());
    }
}
