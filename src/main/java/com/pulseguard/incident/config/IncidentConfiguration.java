package com.pulseguard.incident.config;

import com.pulseguard.incident.metrics.IncidentMetrics;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.repository.IncidentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IncidentConfiguration {

    @Bean
    public ApplicationRunner incidentMetricsInitializer(
            @Autowired(required = false) MeterRegistry meterRegistry,
            IncidentRepository incidentRepository,
            IncidentMetrics incidentMetrics
    ) {
        return args -> {
            if (meterRegistry != null) {
                incidentMetrics.registerOpenIncidentsGauge(
                        meterRegistry,
                        () -> incidentRepository.countByStatus(IncidentStatus.OPEN)
                );
            }
        };
    }
}
