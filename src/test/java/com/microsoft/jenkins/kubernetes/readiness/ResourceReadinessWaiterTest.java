/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Offline tests for {@link ResourceReadinessWaiter}.
 * <p>
 * A scripted state source serves the resource object for each polling round, a fake clock is
 * advanced by the fake sleeper, and every requested sleep is recorded. No real sleeping happens.
 */
public class ResourceReadinessWaiterTest {

    private static final long INTERVAL_MS = 1000L;
    private static final long TIMEOUT_MS = 10000L;

    private FakeClock clock;
    private RecordingSleeper sleeper;
    private ByteArrayOutputStream logBuffer;

    @Before
    public void setUp() {
        clock = new FakeClock(0L);
        sleeper = new RecordingSleeper(clock);
        logBuffer = new ByteArrayOutputStream();
    }

    private ResourceReadinessWaiter newWaiter(long timeoutMillis) {
        return new ResourceReadinessWaiter(
                timeoutMillis,
                INTERVAL_MS,
                clock,
                sleeper,
                new ResourceReadinessEvaluator(),
                new V1betaResourceReadinessEvaluator(),
                new PrintStream(logBuffer));
    }

    /**
     * State source that returns the scripted resource objects one per polling round, then keeps
     * returning the last one. This emulates reading the current object from the cluster without
     * any API access.
     */
    private static final class ScriptedStateSource {
        private final Queue<Object> rounds = new ConcurrentLinkedQueue<Object>();
        private int readCount;

        private ScriptedStateSource(Object... objectsPerRound) {
            rounds.addAll(Arrays.asList(objectsPerRound));
        }

        private Object read() {
            readCount++;
            Object next = rounds.poll();
            if (next == null) {
                throw new IllegalStateException("No more scripted states");
            }
            // Keep serving the final state if polled again.
            rounds.add(next);
            return next;
        }
    }

    /**
     * Fake clock that only moves when the fake sleeper is asked to sleep.
     */
    private static final class FakeClock implements Clock {
        private long now;

        private FakeClock(long start) {
            this.now = start;
        }

        @Override
        public long now() {
            return now;
        }

        private void advance(long millis) {
            now += millis;
        }
    }

    /**
     * Sleeper that records the requested durations and advances the fake clock instead of
     * really blocking the thread.
     */
    private static final class RecordingSleeper implements Sleeper {
        private final FakeClock clock;
        private final List<Long> sleeps = new ArrayList<Long>();

        private RecordingSleeper(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
            clock.advance(millis);
        }
    }

    private static AppliedResource appliedResource(
            final ScriptedStateSource source, String kind, String namespace, String name) {
        return new AppliedResource(kind, namespace, name, new java.util.function.Supplier<Object>() {
            @Override
            public Object get() {
                return source.read();
            }
        });
    }

    private static io.kubernetes.client.openapi.models.V1Deployment deployment(
            long generation, long observedGeneration,
            int desired, int updated, int replicas, int available) {
        return new io.kubernetes.client.openapi.models.V1DeploymentBuilder()
                .withNewMetadata().withName("web").withNamespace("default")
                .withGeneration(generation).endMetadata()
                .withNewSpec().withReplicas(desired).endSpec()
                .withNewStatus()
                .withObservedGeneration(observedGeneration)
                .withUpdatedReplicas(updated).withReplicas(replicas).withAvailableReplicas(available)
                .endStatus()
                .build();
    }

    private static io.kubernetes.client.openapi.models.V1Job failedJob() {
        return new io.kubernetes.client.openapi.models.V1Job()
                .metadata(new io.kubernetes.client.openapi.models.V1ObjectMeta()
                        .name("migrate").namespace("batch"))
                .status(new io.kubernetes.client.openapi.models.V1JobStatus().addConditionsItem(
                        new io.kubernetes.client.openapi.models.V1JobCondition()
                                .type("Failed").status("True")
                                .reason("BackoffLimitExceeded")
                                .message("Job has reached the specified backoff limit")));
    }

    @Test
    public void becomesReadyOnTheThirdRound() throws Exception {
        ScriptedStateSource source = new ScriptedStateSource(
                deployment(1L, 1L, 3, 1, 1, 1),
                deployment(1L, 1L, 3, 2, 2, 2),
                deployment(1L, 1L, 3, 3, 3, 3));
        AppliedResource resource = appliedResource(source, "V1Deployment", "default", "web");

        newWaiter(TIMEOUT_MS).waitUntilReady(java.util.Collections.singletonList(resource));

        // Three evaluation rounds, with a sleep before the second and third round.
        assertEquals(3, source.readCount);
        assertEquals(2, sleeper.sleeps.size());
        assertEquals(INTERVAL_MS, sleeper.sleeps.get(0).longValue());
    }

    @Test
    public void timeoutReportsBothUnreadyResourcesInApplicationOrder() throws Exception {
        ScriptedStateSource first = new ScriptedStateSource(
                deployment(1L, 1L, 3, 2, 3, 1));
        ScriptedStateSource second = new ScriptedStateSource(
                deployment(1L, 1L, 2, 0, 1, 0));
        AppliedResource resourceA = appliedResource(first, "V1Deployment", "default", "web");
        AppliedResource resourceB = appliedResource(second, "V1Deployment", "team-b", "api");

        try {
            newWaiter(2500L).waitUntilReady(Arrays.asList(resourceA, resourceB));
            fail("Expected ResourceNotReadyException");
        } catch (ResourceNotReadyException e) {
            String message = e.getMessage();
            assertTrue(message, message.contains("Timed out after"));
            assertTrue(message, message.contains("ms waiting"));
            // Application order is preserved and both resources are listed.
            int indexOfA = message.indexOf("Deployment default/web");
            int indexOfB = message.indexOf("Deployment team-b/api");
            assertTrue(indexOfA >= 0);
            assertTrue(indexOfB > indexOfA);
            assertTrue(message.contains("updated 2"));
            assertTrue(message.contains("available 1"));
        }
        // The last sleep is shortened to the remaining time rather than overshooting the deadline.
        long totalSlept = 0L;
        for (Long slept : sleeper.sleeps) {
            totalSlept += slept;
        }
        assertEquals(2500L, totalSlept);
    }

    @Test
    public void jobFailingTerminatesImmediatelyWithoutWaitingForTimeout() {
        ScriptedStateSource source = new ScriptedStateSource(failedJob());
        AppliedResource resource = appliedResource(source, "V1Job", "batch", "migrate");

        try {
            newWaiter(TIMEOUT_MS).waitUntilReady(java.util.Collections.singletonList(resource));
            fail("Expected ResourceNotReadyException");
        } catch (ResourceNotReadyException e) {
            assertTrue(e.getMessage().contains("failed state"));
            assertTrue(e.getMessage().contains("Job batch/migrate"));
            assertTrue(e.getMessage().contains("BackoffLimitExceeded"));
        } catch (InterruptedException e) {
            fail("InterruptedException not expected");
        }
        // Deterministic failure ends the wait on the first round: no sleeping at all.
        assertTrue(sleeper.sleeps.isEmpty());
        assertEquals(1, source.readCount);
    }

    @Test
    public void failureRoundAlsoListsOtherUnreadyResourcesInApplicationOrder() {
        // The first applied resource has failed deterministically, the second is still pending.
        ScriptedStateSource failedSource = new ScriptedStateSource(failedJob());
        ScriptedStateSource pendingSource = new ScriptedStateSource(
                deployment(1L, 1L, 1, 0, 0, 0));
        AppliedResource failedResource = appliedResource(failedSource, "V1Job", "batch", "migrate");
        AppliedResource pendingResource = appliedResource(pendingSource, "V1Deployment", "default", "web");

        try {
            newWaiter(TIMEOUT_MS).waitUntilReady(Arrays.asList(failedResource, pendingResource));
            fail("Expected ResourceNotReadyException");
        } catch (ResourceNotReadyException e) {
            String message = e.getMessage();
            // Both resources from the same round are listed, in application order.
            int indexOfJob = message.indexOf("Job batch/migrate");
            int indexOfDeployment = message.indexOf("Deployment default/web");
            assertTrue(indexOfJob >= 0);
            assertTrue(indexOfDeployment > indexOfJob);
            assertTrue(message.contains("BackoffLimitExceeded"));
        } catch (InterruptedException e) {
            fail("InterruptedException not expected");
        }
        // The whole round is evaluated but the wait ends before any sleep.
        assertTrue(sleeper.sleeps.isEmpty());
        assertEquals(1, failedSource.readCount);
        assertEquals(1, pendingSource.readCount);
    }

    @Test
    public void deploymentWithUnsetReplicasConvergesWithDefaultOne() throws Exception {
        // Round 1: spec.replicas unset (defaults to 1), no replica has converged yet.
        io.kubernetes.client.openapi.models.V1Deployment roundOne =
                new io.kubernetes.client.openapi.models.V1DeploymentBuilder()
                        .withNewMetadata().withName("web").withNamespace("default")
                        .withGeneration(1L).endMetadata()
                        .withNewSpec().endSpec()
                        .withNewStatus().withObservedGeneration(1L)
                        .withUpdatedReplicas(0).withReplicas(0).withAvailableReplicas(0)
                        .endStatus().build();
        // Round 2: the single desired replica is fully rolled out.
        io.kubernetes.client.openapi.models.V1Deployment roundTwo =
                new io.kubernetes.client.openapi.models.V1DeploymentBuilder()
                        .withNewMetadata().withName("web").withNamespace("default")
                        .withGeneration(1L).endMetadata()
                        .withNewSpec().endSpec()
                        .withNewStatus().withObservedGeneration(1L)
                        .withUpdatedReplicas(1).withReplicas(1).withAvailableReplicas(1)
                        .endStatus().build();
        ScriptedStateSource source = new ScriptedStateSource(roundOne, roundTwo);
        AppliedResource resource = appliedResource(source, "V1Deployment", "default", "web");

        newWaiter(TIMEOUT_MS).waitUntilReady(java.util.Collections.singletonList(resource));

        assertEquals(2, source.readCount);
    }

    @Test
    public void laggingObservedGenerationKeepsResourcePendingUntilCaughtUp() throws Exception {
        ScriptedStateSource source = new ScriptedStateSource(
                deployment(3L, 2L, 3, 3, 3, 3),
                deployment(3L, 2L, 3, 3, 3, 3),
                deployment(3L, 3L, 3, 3, 3, 3));
        AppliedResource resource = appliedResource(source, "V1Deployment", "default", "web");

        newWaiter(TIMEOUT_MS).waitUntilReady(java.util.Collections.singletonList(resource));

        assertEquals(3, source.readCount);
    }

    @Test
    public void zeroTimeoutSkipsWaitingAndNeverReadsState() throws Exception {
        ScriptedStateSource source = new ScriptedStateSource(
                deployment(1L, 1L, 3, 0, 0, 0));
        AppliedResource resource = appliedResource(source, "V1Deployment", "default", "web");

        newWaiter(0L).waitUntilReady(java.util.Collections.singletonList(resource));

        assertEquals(0, source.readCount);
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    public void interruptedWaitIsPropagated() {
        ScriptedStateSource source = new ScriptedStateSource(
                deployment(1L, 1L, 3, 0, 0, 0));
        AppliedResource resource = appliedResource(source, "V1Deployment", "default", "web");
        ResourceReadinessWaiter waiter = new ResourceReadinessWaiter(
                TIMEOUT_MS,
                INTERVAL_MS,
                clock,
                new Sleeper() {
                    @Override
                    public void sleep(long millis) throws InterruptedException {
                        throw new InterruptedException("stop waiting");
                    }
                },
                new ResourceReadinessEvaluator(),
                new V1betaResourceReadinessEvaluator(),
                new PrintStream(logBuffer));

        try {
            waiter.waitUntilReady(java.util.Collections.singletonList(resource));
            fail("Expected InterruptedException");
        } catch (InterruptedException e) {
            assertEquals("stop waiting", e.getMessage());
        }
    }

    @Test
    public void readyResourcesAreNotPolledAgain() throws Exception {
        ScriptedStateSource readySource = new ScriptedStateSource(
                deployment(1L, 1L, 1, 1, 1, 1));
        ScriptedStateSource pendingSource = new ScriptedStateSource(
                deployment(1L, 1L, 1, 0, 0, 0),
                deployment(1L, 1L, 1, 1, 1, 1));
        AppliedResource readyResource = appliedResource(readySource, "V1Deployment", "default", "ready");
        AppliedResource pendingResource = appliedResource(pendingSource, "V1Deployment", "default", "pending");

        newWaiter(TIMEOUT_MS).waitUntilReady(Arrays.asList(readyResource, pendingResource));

        // The already-ready resource is read once, the pending one twice.
        assertEquals(1, readySource.readCount);
        assertEquals(2, pendingSource.readCount);
    }
}
