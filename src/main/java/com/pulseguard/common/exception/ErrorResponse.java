package com.pulseguard.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<ValidationErrorDetail> validationErrors
) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(Instant.now(), status, error, message, path, null);
    }

    public static ErrorResponse withValidationErrors(
            int status,
            String error,
            String message,
            String path,
            List<ValidationErrorDetail> validationErrors
    ) {
        return new ErrorResponse(Instant.now(), status, error, message, path, validationErrors);
    }
}
