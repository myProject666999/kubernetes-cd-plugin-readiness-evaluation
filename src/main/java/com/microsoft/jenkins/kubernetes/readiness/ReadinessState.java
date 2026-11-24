/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import javax.annotation.Nullable;

/**
 * Tri-state result of evaluating whether a Kubernetes resource is ready after being applied.
 *
 * @see ResourceReadinessEvaluator
 */
public final class ReadinessState {

    /**
     * The resource has reached the desired state.
     */
    public enum Status {
        READY,
        NOT_READY,
        FAILED
    }

    private static final ReadinessState READY_INSTANCE = new ReadinessState(Status.READY, null);

    private final Status status;
    private final String reason;

    private ReadinessState(Status status, String reason) {
        this.status = status;
        this.reason = reason;
    }

    public static ReadinessState ready() {
        return READY_INSTANCE;
    }

    public static ReadinessState notReady(String reason) {
        return new ReadinessState(Status.NOT_READY, reason);
    }

    public static ReadinessState failed(String reason) {
        return new ReadinessState(Status.FAILED, reason);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    /**
     * @return human readable reason for the NOT_READY / FAILED state, {@code null} when READY.
     */
    @Nullable
    public String getReason() {
        return reason;
    }

    @Override
    public String toString() {
        return reason == null ? status.name() : status.name() + "(" + reason + ")";
    }
}
