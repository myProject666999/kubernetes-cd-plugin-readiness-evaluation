/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

/**
 * A source of the current time in milliseconds.
 * <p>
 * Extracted so that the readiness wait loop can be driven by a fake clock in tests instead of
 * calling {@link System#currentTimeMillis()}.
 */
public interface Clock {
    long now();

    Clock SYSTEM = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };
}
