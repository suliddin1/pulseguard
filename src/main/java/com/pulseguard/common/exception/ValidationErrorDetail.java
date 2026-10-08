package com.pulseguard.common.exception;

public record ValidationErrorDetail(
        String field,
        Object rejectedValue,
        String message
) {}
