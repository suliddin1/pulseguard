package com.pulseguard.service.dto;

import jakarta.validation.constraints.NotNull;

public record PatchServiceStatusRequest(
        @NotNull(message = "Enabled state must be provided")
        Boolean enabled
) {}
