package com.pulseguard.incident.config;

import com.pulseguard.incident.model.IncidentSeverity;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "pulseguard.incident")
public class IncidentProperties {

    /**
     * Number of consecutive health check failures required to trigger an incident.
     */
    private int consecutiveFailuresThreshold = 3;

    /**
     * Number of consecutive successful health checks required to resolve an open incident.
     */
    private int consecutiveSuccessesThreshold = 1;

    /**
     * Initial severity assigned to confirmed service unavailability incidents.
     */
    private IncidentSeverity initialSeverity = IncidentSeverity.CRITICAL;

    public int getConsecutiveFailuresThreshold() {
        return consecutiveFailuresThreshold;
    }

    public void setConsecutiveFailuresThreshold(int consecutiveFailuresThreshold) {
        this.consecutiveFailuresThreshold = Math.max(1, consecutiveFailuresThreshold);
    }

    public int getConsecutiveSuccessesThreshold() {
        return consecutiveSuccessesThreshold;
    }

    public void setConsecutiveSuccessesThreshold(int consecutiveSuccessesThreshold) {
        this.consecutiveSuccessesThreshold = Math.max(1, consecutiveSuccessesThreshold);
    }

    public IncidentSeverity getInitialSeverity() {
        return initialSeverity;
    }

    public void setInitialSeverity(IncidentSeverity initialSeverity) {
        if (initialSeverity != null) {
            this.initialSeverity = initialSeverity;
        }
    }
}
