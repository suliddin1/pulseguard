package com.pulseguard;

import com.pulseguard.service.dto.CreateServiceRequest;
import com.pulseguard.service.dto.ServiceResponse;
import com.pulseguard.service.model.ServiceStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@EnabledIf("com.pulseguard.DockerAvailableCondition#isDockerAvailable")
class PulseGuardPostgresTestcontainersIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pulseguard_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("End-to-end integration against real PostgreSQL container via Flyway")
    void shouldRegisterAndRetrieveServiceOnPostgres() {
        String baseUrl = "http://localhost:" + port + "/api/v1/services";

        CreateServiceRequest request = new CreateServiceRequest(
                "Postgres Monitored Service",
                "E2E test with Testcontainers",
                "https://e2e.example.com/health",
                30,
                3000
        );

        ResponseEntity<ServiceResponse> createResponse = restTemplate.postForEntity(
                baseUrl,
                request,
                ServiceResponse.class
        );

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createResponse.getBody()).isNotNull();
        assertThat(createResponse.getBody().name()).isEqualTo("Postgres Monitored Service");
        assertThat(createResponse.getBody().status()).isEqualTo(ServiceStatus.UNKNOWN);

        ResponseEntity<ServiceResponse> getResponse = restTemplate.getForEntity(
                baseUrl + "/" + createResponse.getBody().id(),
                ServiceResponse.class
        );

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().id()).isEqualTo(createResponse.getBody().id());
    }
}
