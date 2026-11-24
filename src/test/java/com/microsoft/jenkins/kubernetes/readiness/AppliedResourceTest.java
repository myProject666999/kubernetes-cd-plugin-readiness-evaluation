/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link AppliedResource}.
 */
public class AppliedResourceTest {

    @Test
    public void reportKindStripsVersionPrefix() {
        AppliedResource v1 = new AppliedResource("V1Deployment", "default", "web", null);
        AppliedResource beta2 = new AppliedResource("V1beta2Deployment", "default", "web", null);
        AppliedResource extensions = new AppliedResource(
                "ExtensionsV1beta1Deployment", "default", "web", null);
        AppliedResource configMap = new AppliedResource("V1ConfigMap", "default", "cfg", null);

        assertEquals("Deployment", v1.getReportKind());
        assertEquals("Deployment", beta2.getReportKind());
        assertEquals("Deployment", extensions.getReportKind());
        assertEquals("ConfigMap", configMap.getReportKind());
    }

    @Test
    public void readCurrentDelegatesToTheSupplier() {
        final AtomicInteger calls = new AtomicInteger();
        AppliedResource resource = new AppliedResource(
                "V1Pod", "default", "pod", new java.util.function.Supplier<Object>() {
                    @Override
                    public Object get() {
                        return calls.incrementAndGet();
                    }
                });

        assertEquals(1, resource.readCurrent());
        assertEquals(2, resource.readCurrent());
        assertEquals(2, calls.get());
        assertEquals("V1Pod", resource.getKind());
        assertEquals("default", resource.getNamespace());
        assertEquals("pod", resource.getName());
        assertEquals("Pod default/pod", resource.toString());
    }
}
