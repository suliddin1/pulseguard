package com.pulseguard.incident;

import com.pulseguard.healthcheck.dto.HealthCheckResponse;
import com.pulseguard.healthcheck.model.HealthCheckResult;
import com.pulseguard.healthcheck.prober.HttpHealthProber;
import com.pulseguard.healthcheck.prober.ProbeResult;
import com.pulseguard.healthcheck.service.HealthCheckExecutionService;
import com.pulseguard.incident.dto.IncidentResponse;
import com.pulseguard.incident.model.IncidentSeverity;
import com.pulseguard.incident.model.IncidentStatus;
import com.pulseguard.incident.model.IncidentType;
import com.pulseguard.incident.repository.IncidentRepository;
import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.service.ServiceManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class IncidentLifecycleIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ServiceManagementService serviceManagementService;

    @Autowired
    private HealthCheckExecutionService healthCheckExecutionService;

    @Autowired
    private IncidentRepository incidentRepository;

    @MockBean
    private HttpHealthProber httpHealthProber;

    @Test
    @DisplayName("End-to-end incident lifecycle: failures create incident, further failures increment occurrences, success resolves incident")
    void fullIncidentLifecycleIntegration() {
        // 1. Register a new service
        CreateServiceRequest createRequest = new CreateServiceRequest(
                "Inventory Service",
                "Warehouse stock API",
                "https://inventory.example.com/health",
                30,
                3000
        );
        ServiceResponse service = serviceManagementService.createService(createRequest);
        UUID serviceId = service.id();

        // 2. Simulate 2 failures -> below threshold of 3, no incident
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(new ProbeResult(HealthCheckResult.FAILURE, 500, 250, "Internal Server Error"));

        healthCheckExecutionService.executeHealthCheck(serviceId);
        healthCheckExecutionService.executeHealthCheck(serviceId);

        assertThat(incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN)).isEmpty();

        // 3. 3rd consecutive failure -> threshold reached, incident created
        healthCheckExecutionService.executeHealthCheck(serviceId);

        var openIncidents = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openIncidents).hasSize(1);
        var incident = openIncidents.get(0);
        assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
        assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(incident.getIncidentType()).isEqualTo(IncidentType.SERVICE_UNAVAILABLE);
        assertThat(incident.getOccurrenceCount()).isEqualTo(3L);

        // 4. 4th failure -> updates occurrence count to 4, does NOT create duplicate
        healthCheckExecutionService.executeHealthCheck(serviceId);

        var openAfter4th = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openAfter4th).hasSize(1);
        assertThat(openAfter4th.get(0).getOccurrenceCount()).isEqualTo(4L);

        // 5. Query open incidents REST endpoint: GET /api/v1/incidents/open
        ResponseEntity<Map> openResponse = restTemplate.getForEntity("/api/v1/incidents/open", Map.class);
        assertThat(openResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(openResponse.getBody()).isNotNull();

        // 6. Simulate successful check -> incident automatically resolved
        when(httpHealthProber.probe(anyString(), anyInt()))
                .thenReturn(new ProbeResult(HealthCheckResult.SUCCESS, 200, 45, null));

        healthCheckExecutionService.executeHealthCheck(serviceId);

        var openAfterRecovery = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.OPEN);
        assertThat(openAfterRecovery).isEmpty();

        var resolvedIncidents = incidentRepository.findByServiceIdAndStatus(serviceId, IncidentStatus.RESOLVED);
        assertThat(resolvedIncidents).hasSize(1);
        var resolved = resolvedIncidents.get(0);
        assertThat(resolved.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getDetails()).contains("Service recovered");

        // 7. Verify service incident history via REST: GET /api/v1/services/{id}/incidents
        ResponseEntity<Map> historyResponse = restTemplate.getForEntity(
                "/api/v1/services/" + serviceId + "/incidents", Map.class
        );
        assertThat(historyResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(historyResponse.getBody()).isNotNull();
    }
}
