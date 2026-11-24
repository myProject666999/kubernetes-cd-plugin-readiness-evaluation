/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

/**
 * Blocking abstraction used by the readiness wait loop.
 * <p>
 * Extracted so that tests can substitute a fake sleeper that records the requested sleep durations
 * and advances a fake clock, instead of really sleeping.
 */
public interface Sleeper {
    void sleep(long millis) throws InterruptedException;

    Sleeper SYSTEM = new Sleeper() {
        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }
    };
}
