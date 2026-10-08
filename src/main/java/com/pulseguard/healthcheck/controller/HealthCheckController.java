package com.pulseguard.healthcheck.controller;

import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/services/{serviceId}/checks")
public class HealthCheckController {

    private final HealthCheckExecutionService healthCheckExecutionService;

    public HealthCheckController(HealthCheckExecutionService healthCheckExecutionService) {
        this.healthCheckExecutionService = healthCheckExecutionService;
    }

    @PostMapping
    public ResponseEntity<HealthCheckResponse> triggerHealthCheck(@PathVariable UUID serviceId) {
        HealthCheckResponse response = healthCheckExecutionService.executeHealthCheck(serviceId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<HealthCheckResponse>> getHistoricalChecks(
            @PathVariable UUID serviceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @PageableDefault(size = 20, sort = "checkedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        Page<HealthCheckResponse> checks = healthCheckExecutionService.getHistoricalChecks(serviceId, from, to, pageable);
        return ResponseEntity.ok(checks);
    }
}
