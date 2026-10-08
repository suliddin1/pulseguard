package com.pulseguard;

import org.testcontainers.DockerClientFactory;

public final class DockerAvailableCondition {

    private DockerAvailableCondition() {}

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
