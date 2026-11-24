/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for
 * license information.
 */

package com.microsoft.jenkins.kubernetes.readiness;

import org.apache.commons.lang3.StringUtils;

import java.util.function.Supplier;

/**
 * An applied resource that can be re-read from the cluster and evaluated for readiness.
 * <p>
 * Instances are produced by
 * {@link com.microsoft.jenkins.kubernetes.wrapper.ResourceManager.ResourceUpdater} at apply time
 * and keep the application order, which is used for the timeout / failure report.
 */
public class AppliedResource {

    private static final String VERSION_PREFIX_PATTERN =
            "^(ExtensionsV[0-9]+(alpha|beta)[0-9]+|V[0-9]+(alpha|beta)[0-9]+|V[0-9]+)";

    private final String kind;
    private final String namespace;
    private final String name;
    private final Supplier<Object> currentResourceSupplier;

    public AppliedResource(String kind, String namespace, String name,
                           Supplier<Object> currentResourceSupplier) {
        this.kind = kind;
        this.namespace = namespace;
        this.name = name;
        this.currentResourceSupplier = currentResourceSupplier;
    }

    /**
     * Re-read the current object of the resource from the cluster through the owning updater's
     * read-only entry.
     *
     * @return the current resource object, or {@code null} if it is not observed yet.
     */
    public Object readCurrent() {
        return currentResourceSupplier.get();
    }

    public String getKind() {
        return kind;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    /**
     * Strip the Java client version prefix from the model class name so that the report shows the
     * plain Kubernetes kind, e.g. {@code V1beta2Deployment} becomes {@code Deployment}.
     */
    public String getReportKind() {
        return kind.replaceFirst(VERSION_PREFIX_PATTERN, "");
    }

    @Override
    public String toString() {
        return StringUtils.join(getReportKind(), ' ', namespace, '/', name);
    }
}
