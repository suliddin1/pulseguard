package com.pulseguard.common.exception;

public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }

    public DuplicateResourceException(String resourceName, String field, Object value) {
        super(String.format("%s already exists with %s: '%s'", resourceName, field, value));
    }
}
