# PulseGuard

[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.3.5-6DB33F?style=for-the-badge&logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-316192?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Flyway](https://img.shields.io/badge/Flyway-Database_Migrations-CC0202?style=for-the-badge&logo=flyway&logoColor=white)](https://flywaydb.org/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=for-the-badge&logo=docker&logoColor=white)](https://www.docker.com/)

**PulseGuard** is a developer-focused, production-grade system health and service monitoring platform. It provides automated monitoring of distributed services, tracks health checks, analyzes availability and response times, detects abnormal behaviors, and manages incidents throughout their lifecycle.

Built as a production-quality backend system, PulseGuard emphasizes clean layered architecture, strict encapsulation, type-safe API contracts, database migration discipline, and automated testing.

---

## Architecture Overview

PulseGuard follows a clean layered architecture with clear separation of concerns:

```
[ HTTP Requests ]
       │
       ▼
┌───────────────────────────────────────────────┐
│              Presentation Layer               │
│  - REST Controllers (ServiceController)       │
│  - Bean Validation & Content Negotiation     │
│  - Global Exception Handling (RFC 7807)       │
└──────────────────────┬────────────────────────┘
                       │ DTOs (Request / Response)
                       ▼
┌───────────────────────────────────────────────┐
│                Business Layer                 │
│  - ServiceManagementService                   │
│  - Transaction Management (@Transactional)    │
│  - Business Invariants & Lifecycle Rules      │
└──────────────────────┬────────────────────────┘
                       │ Domain Entities
                       ▼
┌───────────────────────────────────────────────┐
│               Persistence Layer               │
│  - Spring Data JPA (ServiceRepository)        │
│  - Auditing & Optimistic Locking              │
│  - Flyway Versioned Migrations                │
└──────────────────────┬────────────────────────┘
                       │ SQL
                       ▼
┌───────────────────────────────────────────────┐
│             PostgreSQL 16 Database            │
│  - B-tree Indexes, Foreign Keys, Constraints  │
└───────────────────────────────────────────────┘
```

### Key Engineering Decisions

1. **Domain vs. API Separation**:
   - JPA entities (`MonitoredService`) are strictly encapsulated within the persistence/service layer and are never directly exposed via controllers.
   - Immutable Java 21 `record` DTOs (`CreateServiceRequest`, `UpdateServiceRequest`, `ServiceResponse`) define explicit, versioned API contracts.

2. **Database Migration Governance**:
   - Schema creation and modifications are governed exclusively via version-controlled Flyway migrations (`db/migration/V*`).
   - Hibernate's `ddl-auto` is strictly set to `validate` in production, eliminating uncontrolled runtime schema drift.
   - Comprehensive B-tree indexes are applied on high-cardinality and filterable columns (`status`, `enabled`, `created_at`).

3. **Consistent Error Model**:
   - Centralized `@RestControllerAdvice` (`GlobalExceptionHandler`) intercepts domain exceptions, validation violations, and unexpected runtime faults.
   - Standardized `ErrorResponse` guarantees consistent payload structures, UTC timestamps, and field-level validation breakdowns.

4. **Reliable Concurrency & Time Handling**:
   - All timestamps (`createdAt`, `updatedAt`) use `java.time.Instant` representing UTC points in time.
   - Spring Data JPA auditing automatically tracks entity lifecycles.

---

## Tech Stack

| Component | Technology | Version | Description |
|-----------|------------|---------|-------------|
| Language | Java | 21+ | Modern LTS Java runtime |
| Framework | Spring Boot | 3.3.5 | Production web framework |
| Persistence | Spring Data JPA / Hibernate | 6.5+ | Object-relational mapping |
| Database | PostgreSQL | 16 | Primary relational datastore |
| Migrations | Flyway | 10.10+ | Schema versioning and evolution |
| Validation | Jakarta Bean Validation | 3.0+ | Declarative input validation |
| Observability | Spring Boot Actuator | 3.3.5 | Health checks, metrics, and diagnostics |
| Testing | JUnit 5, Mockito, MockMvc | 5.10+ | Unit and slice testing |
| Integration | Testcontainers | 1.20+ | Real containerized PostgreSQL testing |
| Containers | Docker / Docker Compose | Compose v2 | Local infrastructure orchestration |

---

## Database Schema

Database migrations are managed via version-controlled Flyway scripts:

### Phase 1 — Monitored Services (`V1__create_services_table.sql`)
```sql
CREATE TABLE IF NOT EXISTS services (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    url VARCHAR(2048) NOT NULL,
    check_interval_seconds INTEGER NOT NULL DEFAULT 60,
    timeout_ms INTEGER NOT NULL DEFAULT 5000,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_services_name UNIQUE (name),
    CONSTRAINT chk_services_check_interval CHECK (check_interval_seconds >= 5 AND check_interval_seconds <= 86400),
    CONSTRAINT chk_services_timeout CHECK (timeout_ms >= 500 AND timeout_ms <= 60000)
);

CREATE INDEX idx_services_status ON services(status);
CREATE INDEX idx_services_enabled ON services(enabled);
CREATE INDEX idx_services_created_at ON services(created_at);
```

### Phase 2 — Health Checks (`V2__create_health_checks_table.sql`)
```sql
CREATE TABLE IF NOT EXISTS health_checks (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    response_time_ms BIGINT NOT NULL,
    http_status_code INTEGER,
    result VARCHAR(30) NOT NULL,
    error_message VARCHAR(1000),
    CONSTRAINT fk_health_checks_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    CONSTRAINT chk_health_checks_response_time CHECK (response_time_ms >= 0)
);

CREATE INDEX idx_health_checks_service_id ON health_checks(service_id);
CREATE INDEX idx_health_checks_checked_at ON health_checks(checked_at);
CREATE INDEX idx_health_checks_service_checked_at ON health_checks(service_id, checked_at DESC);
CREATE INDEX idx_health_checks_result ON health_checks(result);
```

---

## Health Check Engine

PulseGuard features an HTTP health probing mechanism built on modern Java 21 standard library capabilities (`java.net.http.HttpClient`):

### 1. Probe Lifecycle
1. **Validation & Resolution**: Resolves target service URL, verifies the service is enabled, and enforces configured per-service timeout limits.
2. **Probing**: Dispatches non-blocking HTTP GET requests using `HttpResponse.BodyHandlers.discarding()`, preventing heap memory allocation from large remote response payloads.
3. **High-Precision Timing**: Measures execution latency at millisecond precision using `System.nanoTime()`.
4. **Fault Tolerance**: Isolates external service failures (DNS errors, connection refusals, timeouts, HTTP 5xx). External service failures never crash the monitoring engine.
5. **Atomic State Transition**: Persists the check execution record and transitions the service's health status in a single transaction.

### 2. Check Result States
* **`SUCCESS`**: The target endpoint responded with an HTTP `2xx` status code within the configured timeout.
* **`FAILURE`**: The endpoint returned an HTTP non-2xx status code (e.g. 404, 500, 503) or an I/O error occurred (connection refused, host unreachable, malformed URL).
* **`TIMEOUT`**: The target failed to respond within the service's configured `timeoutMs` threshold.

### 3. Service Status Transitions
* **Transition to `HEALTHY`**: Triggered when a health check yields `SUCCESS`.
* **Transition to `UNHEALTHY`**: Triggered when a health check yields `FAILURE` or `TIMEOUT`.
* **Disabled Services**: Services marked `enabled: false` reject manual and scheduled probes with HTTP 400 Bad Request (`ServiceDisabledException`), protecting inactive workloads.

---

## Automated Background Scheduling Engine

PulseGuard features an automated background scheduling subsystem that executes periodic health checks according to each service's configured `checkIntervalSeconds`:

```
┌─────────────────────────────────────────────────────────────┐
│                 Spring @Scheduled Poller                    │
│            (fixedDelayString = "pollingIntervalMs")          │
└──────────────────────────────┬──────────────────────────────┘
                               │ Polls enabled services
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                    DueCheckEvaluator                        │
│   - Checks lastCheckedAt + checkIntervalSeconds <= now      │
│   - New/unverified services scheduled immediately           │
└──────────────────────────────┬──────────────────────────────┘
                               │ Eligible service IDs
                               ▼
┌─────────────────────────────────────────────────────────────┐
│              Same-Service Overlap Protection                │
│   - Atomic check reservation via ConcurrentHashSet          │
│   - Skips tick if check is already running for service      │
└──────────────────────────────┬──────────────────────────────┘
                               │ Dispatches check task
                               ▼
┌─────────────────────────────────────────────────────────────┐
│          Bounded ThreadPoolTaskExecutor (Worker Pool)       │
│   - Core / Max Pool Size: configurable (e.g. 10 workers)    │
│   - Queue Capacity: configurable (e.g. 50 tasks)            │
│   - Rejection Policy: AbortPolicy with graceful catch       │
│   - Graceful Shutdown: waitForTasksToCompleteOnShutdown     │
└─────────────────────────────────────────────────────────────┘
```

### Key Scheduling Capabilities

1. **Independent Per-Service Intervals**: Each service defines its own `checkIntervalSeconds` (e.g. Service A every 10s, Service B every 60s, Service C every 5m). The engine dynamically evaluates due status on every tick without creating unbounded threads per service.
2. **Bounded Concurrency & Backpressure**: Background checks execute on a dedicated Spring `ThreadPoolTaskExecutor`. When queue capacity is reached, rejections are handled gracefully without aborting the poller.
3. **Same-Service Overlap Protection**: If a previous check for Service A is still executing or timing out, subsequent scheduler cycles skip Service A until the existing check finishes, preventing check pile-ups.
4. **Slow Service & Fault Isolation**: Long response times or uncaught exceptions from one service do not block or delay health checks of other services.
5. **Dynamic Eligibility**: Newly added or re-enabled services become immediately eligible for monitoring without requiring an application restart.
6. **Graceful Application Shutdown**: The worker pool is configured with `setWaitForTasksToCompleteOnShutdown(true)` and an await termination timeout, ensuring active probes complete cleanly during SIGTERM/shutdown.
7. **Production Observability & Metrics**: Built-in thread-safe counters and Micrometer metrics track:
   - `pulseguard.scheduler.scheduled`: Total checks scheduled
   - `pulseguard.scheduler.started`: Checks that began execution
   - `pulseguard.scheduler.completed`: Successfully finished check tasks
   - `pulseguard.scheduler.failed`: Checks that threw unexpected exceptions
   - `pulseguard.scheduler.skipped`: Due checks skipped due to an in-flight check
   - `pulseguard.scheduler.rejected`: Checks rejected due to executor capacity exhaustion
   - `pulseguard.scheduler.inflight`: Current number of concurrently running checks

### Scheduler Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `pulseguard.scheduler.enabled` | `true` | Master toggle to enable or disable the background scheduling engine |
| `pulseguard.scheduler.polling-interval-ms` | `5000` | Delay between polling iterations in milliseconds |
| `pulseguard.scheduler.initial-delay-ms` | `2000` | Delay after startup before the initial polling cycle begins |
| `pulseguard.scheduler.max-concurrent-checks` | `10` | Maximum worker threads in the health check thread pool |
| `pulseguard.scheduler.queue-capacity` | `50` | Maximum queue size for pending health check tasks |
| `pulseguard.scheduler.termination-timeout-seconds` | `30` | Maximum grace period for in-flight tasks during shutdown |

---

## REST API Specification

### Service Management (`/api/v1/services`)

#### 1. Register Monitored Service
* **Endpoint**: `POST /api/v1/services`
* **Status**: `201 Created`
* **Headers**: `Location: /api/v1/services/{id}`

**Request Body**:
```json
{
  "name": "Payment Gateway API",
  "description": "Primary Stripe and PayPal processing cluster",
  "url": "https://api.payment.internal/health",
  "checkIntervalSeconds": 30,
  "timeoutMs": 3000
}
```

**Response Body**:
```json
{
  "id": "c56a4180-65aa-42ec-a945-5fd21dec0538",
  "name": "Payment Gateway API",
  "description": "Primary Stripe and PayPal processing cluster",
  "url": "https://api.payment.internal/health",
  "checkIntervalSeconds": 30,
  "timeoutMs": 3000,
  "enabled": true,
  "status": "UNKNOWN",
  "createdAt": "2026-10-08T17:30:00Z",
  "updatedAt": "2026-10-08T17:30:00Z"
}
```

#### 2. List Monitored Services
* **Endpoint**: `GET /api/v1/services`
* **Query Parameters**:
  * `enabled` (optional, boolean): Filter by active status (`true` / `false`)
  * `status` (optional, string): Filter by health status (`UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNHEALTHY`)
  * `page` (optional, int, default `0`): Page index
  * `size` (optional, int, default `20`): Page size
  * `sort` (optional, string, default `createdAt,desc`): Sort property and direction
* **Status**: `200 OK`

#### 3. Get Service by ID
* **Endpoint**: `GET /api/v1/services/{id}`
* **Status**: `200 OK` (or `404 Not Found`)

#### 4. Update Service Configuration
* **Endpoint**: `PUT /api/v1/services/{id}`
* **Status**: `200 OK`

#### 5. Toggle Service Enabled Status
* **Endpoint**: `PATCH /api/v1/services/{id}/status`
* **Status**: `200 OK`

#### 6. Delete Monitored Service
* **Endpoint**: `DELETE /api/v1/services/{id}`
* **Status**: `204 No Content` (cascades deletion of historical health checks)

---

### Health Check Engine (`/api/v1/services/{id}/checks`)

#### 7. Trigger Manual Health Check
* **Endpoint**: `POST /api/v1/services/{serviceId}/checks`
* **Status**: `200 OK`

**Response Body**:
```json
{
  "id": "b103e33f-8012-4217-bf20-7469a5ad5682",
  "serviceId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
  "serviceName": "Payment Gateway API",
  "checkedAt": "2026-10-08T17:31:15.820Z",
  "responseTimeMs": 112,
  "httpStatusCode": 200,
  "result": "SUCCESS",
  "errorMessage": null
}
```

#### 8. Retrieve Historical Health Checks
* **Endpoint**: `GET /api/v1/services/{serviceId}/checks`
* **Query Parameters**:
  * `from` (optional, ISO-8601 timestamp): Filter checks after this timestamp (e.g. `2026-10-08T00:00:00Z`)
  * `to` (optional, ISO-8601 timestamp): Filter checks before this timestamp (e.g. `2026-10-08T23:59:59Z`)
  * `page` (optional, int, default `0`): Page index
  * `size` (optional, int, default `20`, max `100`): Page size
  * `sort` (optional, string, default `checkedAt,desc`): Newest checks first
* **Status**: `200 OK`

**Response Body**:
```json
{
  "content": [
    {
      "id": "b103e33f-8012-4217-bf20-7469a5ad5682",
      "serviceId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
      "serviceName": "Payment Gateway API",
      "checkedAt": "2026-10-08T17:31:15.820Z",
      "responseTimeMs": 112,
      "httpStatusCode": 200,
      "result": "SUCCESS",
      "errorMessage": null
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20
  },
  "totalElements": 1,
  "totalPages": 1
}
```

---

## Error Handling

All error responses adhere to a consistent structure:

```json
{
  "timestamp": "2026-10-08T17:35:10.123Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed for one or more fields",
  "path": "/api/v1/services",
  "validationErrors": [
    {
      "field": "url",
      "rejectedValue": "invalid-url",
      "message": "URL must be a valid HTTP or HTTPS address"
    }
  ]
}
```

---

## Local Development & Setup

### Prerequisites

* Java 21 or higher installed (`java -version`)
* Docker & Docker Compose (optional for standalone mode, recommended for PostgreSQL)

### 1. Start PostgreSQL with Docker Compose

```bash
docker compose up -d
```

### 2. Configure Environment Variables

Copy `.env.example` to `.env` or set environment variables:
```bash
cp .env.example .env
```

### 3. Build & Run Application

Using Maven wrapper:
```bash
# On Linux / macOS
./mvnw clean spring-boot:run

# On Windows
.\mvnw.cmd clean spring-boot:run
```

Once started, the application will be accessible at:
- Service API: `http://localhost:8080/api/v1/services`
- Actuator Health: `http://localhost:8080/actuator/health`

---

## Running Automated Tests

PulseGuard features a multi-tiered test suite covering unit, slice, and integration tests:

```bash
# Run all tests
.\mvnw.cmd test
```

* **Domain Unit Tests** (`MonitoredServiceTest`, `HealthCheckTest`): Validates entity lifecycle, state mutations, clamping, and invariant enforcement.
* **Prober Unit Tests** (`JavaHttpHealthProberTest`): Validates HTTP probing, latency measurement, timeout handling, and connection error handling using an embedded JDK `HttpServer`.
* **Scheduler Unit Tests** (`DueCheckEvaluatorTest`, `HealthCheckSchedulerTest`): Validates dynamic due evaluation across unverified/verified services, time interval boundaries, overlap protection, and executor capacity rejection.
* **Scheduler Concurrency & Lifecycle Tests** (`SchedulerConcurrencyTest`, `SchedulerLifecycleIntegrationTest`): Tests parallel multi-service execution, slow-service non-blocking guarantees via synchronization latches, and Spring Boot application lifecycle startup/shutdown.
* **Service Layer Unit Tests** (`ServiceManagementServiceTest`, `HealthCheckExecutionServiceTest`): Tests business logic, unique constraints, health check execution, and repository interactions using Mockito.
* **Controller Slice Tests** (`ServiceControllerTest`, `HealthCheckControllerTest`): Validates HTTP contract, Bean Validation rules, JSON serialization, and error mapping using `MockMvc`.
* **Repository Slice Tests** (`ServiceRepositoryTest`, `HealthCheckRepositoryTest`): Tests JPA mappings, Flyway migrations (`V1` and `V2`), historical ordering, group aggregation queries, and cascade deletes with `@DataJpaTest`.
* **PostgreSQL Testcontainers** (`PulseGuardPostgresTestcontainersIntegrationTest`): Runs end-to-end against real PostgreSQL when a Docker daemon is available.

---

## Roadmap

* **Phase 1 (Completed)**: Core service registry foundation, PostgreSQL + Flyway persistence, layered architecture, DTO isolation, Bean Validation, Global Exception Handling, and comprehensive test suite.
* **Phase 2 (Completed)**: Health Check Execution Engine with standard Java 21 `HttpClient`, latency measurement, status code capturing, manual check trigger (`POST /api/v1/services/{id}/checks`), historical checks pagination (`GET /api/v1/services/{id}/checks`), and Flyway `V2` migration.
* **Phase 3 (Completed)**: Automated background scheduling engine with bounded `ThreadPoolTaskExecutor`, independent per-service check intervals, same-service overlap protection, failure isolation, graceful shutdown, and scheduler Micrometer metrics.
* **Phase 4**: Incident generation & recovery engine, incident lifecycle (`/api/v1/services/{id}/incidents`), anomaly detection algorithms, and alerting channels (Slack, Webhooks, Email).

