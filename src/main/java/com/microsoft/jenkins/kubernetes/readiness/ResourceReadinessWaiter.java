/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin polling loop on top of the pure readiness evaluators.
 * <p>
 * After all resources have been applied, the waiter polls them with one shared deadline:
 * <ol>
 *     <li>every round only the resources that were not ready in the previous round are
 *     re-read from the cluster and re-evaluated;</li>
 *     <li>a resource in the {@link ReadinessState.Status#FAILED FAILED} state terminates the
 *     wait immediately, without waiting for the deadline;</li>
 *     <li>when the deadline is reached, all still-unready resources are reported in their
 *     original application order.</li>
 * </ol>
 * The waiter itself contains no readiness rules and no API calls: those live in the evaluators
 * and {@link AppliedResource#readCurrent()} respectively.
 */
public class ResourceReadinessWaiter {

    /**
     * Polling interval. The timeout is configurable per step; the interval is kept as an
     * internal constant because the step option only exposes the upper bound of the wait.
     */
    public static final long DEFAULT_POLL_INTERVAL_MS = 5000L;

    private final long timeoutMillis;
    private final long intervalMillis;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ResourceReadinessEvaluator evaluator;
    private final V1betaResourceReadinessEvaluator betaEvaluator;
    private final PrintStream logger;

    public ResourceReadinessWaiter(long timeoutMillis, PrintStream logger) {
        this(timeoutMillis, DEFAULT_POLL_INTERVAL_MS, Clock.SYSTEM, Sleeper.SYSTEM,
                new ResourceReadinessEvaluator(), new V1betaResourceReadinessEvaluator(), logger);
    }

    public ResourceReadinessWaiter(
            long timeoutMillis,
            long intervalMillis,
            Clock clock,
            Sleeper sleeper,
            ResourceReadinessEvaluator evaluator,
            V1betaResourceReadinessEvaluator betaEvaluator,
            PrintStream logger) {
        this.timeoutMillis = timeoutMillis;
        this.intervalMillis = intervalMillis;
        this.clock = clock;
        this.sleeper = sleeper;
        this.evaluator = evaluator;
        this.betaEvaluator = betaEvaluator;
        this.logger = logger;
    }

    /**
     * Poll the given applied resources until all are ready, one fails, or the deadline is reached.
     *
     * @param appliedResources resources in application order
     * @throws ResourceNotReadyException if a resource fails deterministically or some resources
     *                                   are still not ready when the deadline is reached
     * @throws InterruptedException      if the wait is interrupted
     */
    public void waitUntilReady(List<AppliedResource> appliedResources) throws InterruptedException {
        if (timeoutMillis <= 0 || appliedResources == null || appliedResources.isEmpty()) {
            // 0 means "do not wait": the historical pipeline behavior is preserved.
            return;
        }

        List<AppliedResource> pending = new ArrayList<>(appliedResources);
        long deadline = clock.now() + timeoutMillis;

        while (!pending.isEmpty()) {
            // Evaluate the whole round first so that a terminal failure still leaves a complete,
            // application-ordered report of every unfinished resource observed in this round.
            List<UnfinishedResource> unfinished = new ArrayList<UnfinishedResource>();
            List<AppliedResource> stillPending = new ArrayList<AppliedResource>();
            boolean failed = false;
            for (AppliedResource resource : pending) {
                ReadinessState state = evaluate(resource);
                if (state.isReady()) {
                    continue;
                }
                unfinished.add(new UnfinishedResource(resource, state.getReason()));
                if (state.isFailed()) {
                    failed = true;
                } else {
                    stillPending.add(resource);
                }
            }
            pending = stillPending;

            if (failed) {
                // A deterministic failure ends the wait in the current round; no extra sleeping.
                throw new ResourceNotReadyException(buildReport(
                        Messages.ResourceReadinessWaiter_failed(), unfinished, appliedResources));
            }
            if (pending.isEmpty()) {
                return;
            }
            if (clock.now() >= deadline) {
                throw new ResourceNotReadyException(buildReport(
                        Messages.ResourceReadinessWaiter_timeout(timeoutMillis), unfinished, appliedResources));
            }
            long remaining = deadline - clock.now();
            sleeper.sleep(Math.min(intervalMillis, remaining));
        }
    }

    private ReadinessState evaluate(AppliedResource resource) {
        Object current = resource.readCurrent();
        // The v1 evaluator returns READY for everything it does not cover; the beta evaluator
        // then gets a chance for the legacy workload models.
        ReadinessState state = evaluator.evaluate(current);
        if (state.isReady() && current != null) {
            state = betaEvaluator.evaluate(current);
        }
        return state;
    }

    private String buildReport(
            String header, List<UnfinishedResource> unfinished, List<AppliedResource> appliedResources) {
        StringBuilder report = new StringBuilder(header);
        // The unfinished entries are reordered by application order for a stable, readable report.
        for (AppliedResource resource : appliedResources) {
            for (UnfinishedResource entry : unfinished) {
                if (entry.resource == resource) {
                    report.append(System.lineSeparator())
                            .append(Messages.ResourceReadinessWaiter_resourceLine(
                                    resource.getReportKind(),
                                    resource.getNamespace(),
                                    resource.getName(),
                                    String.valueOf(entry.reason)));
                    break;
                }
            }
        }
        String message = report.toString();
        if (logger != null) {
            logger.println(message);
        }
        return message;
    }

    /**
     * An applied resource that did not finish, together with the last observed reason.
     */
    private static final class UnfinishedResource {
        private final AppliedResource resource;
        private final String reason;

        private UnfinishedResource(AppliedResource resource, String reason) {
            this.resource = resource;
            this.reason = reason;
        }
    }
}
