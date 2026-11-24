/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

/**
 * Raised when applied resources do not become ready before the deadline, or when one of them
 * reaches a terminal failure state. The message lists every unfinished resource in application
 * order, with its kind, namespace/name and the reason.
 */
public class ResourceNotReadyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ResourceNotReadyException(String message) {
        super(message);
    }
}
