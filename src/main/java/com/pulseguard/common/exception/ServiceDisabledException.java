package com.pulseguard.common.exception;

import java.util.UUID;

public class ServiceDisabledException extends RuntimeException {

    public ServiceDisabledException(UUID serviceId, String serviceName) {
        super(String.format("Cannot execute health check on disabled service '%s' (ID: %s)", serviceName, serviceId));
    }
}
