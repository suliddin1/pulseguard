package com.pulseguard.service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateServiceRequest(
        @NotBlank(message = "Service name is required")
        @Size(min = 2, max = 100, message = "Service name must be between 2 and 100 characters")
        String name,

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description,

        @NotBlank(message = "URL is required")
        @Pattern(
                regexp = "^https?://[a-zA-Z0-9.-]+(:[0-9]+)?(/.*)?$",
                message = "URL must be a valid HTTP or HTTPS address"
        )
        @Size(max = 2048, message = "URL cannot exceed 2048 characters")
        String url,

        @Min(value = 5, message = "Check interval must be at least 5 seconds")
        @Max(value = 86400, message = "Check interval cannot exceed 86400 seconds (24 hours)")
        Integer checkIntervalSeconds,

        @Min(value = 500, message = "Timeout must be at least 500 milliseconds")
        @Max(value = 60000, message = "Timeout cannot exceed 60000 milliseconds (60 seconds)")
        Integer timeoutMs
) {

    public int getResolvedCheckIntervalSeconds() {
        return (checkIntervalSeconds != null && checkIntervalSeconds > 0) ? checkIntervalSeconds : 60;
    }

    public int getResolvedTimeoutMs() {
        return (timeoutMs != null && timeoutMs > 0) ? timeoutMs : 5000;
    }
}
