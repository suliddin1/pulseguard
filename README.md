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

### Phase 4 — Incidents (`V3__create_incidents_table.sql` & `V3_1__create_unique_open_incident_index.sql`)
```sql
CREATE TABLE IF NOT EXISTS incidents (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL,
    incident_type VARCHAR(50) NOT NULL,
    severity VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(255) NOT NULL,
    details VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurrence_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_incidents_service FOREIGN KEY (service_id) REFERENCES services(id) ON DELETE CASCADE,
    CONSTRAINT chk_incidents_occurrence_count CHECK (occurrence_count >= 1),
    CONSTRAINT chk_incidents_status CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT chk_incidents_severity CHECK (severity IN ('WARNING', 'CRITICAL')),
    CONSTRAINT chk_incidents_type CHECK (incident_type IN ('SERVICE_UNAVAILABLE', 'HIGH_LATENCY', 'HIGH_ERROR_RATE'))
);

CREATE INDEX idx_incidents_service_id ON incidents(service_id);
CREATE INDEX idx_incidents_status ON incidents(status);
CREATE INDEX idx_incidents_severity ON incidents(severity);
CREATE INDEX idx_incidents_type ON incidents(incident_type);
CREATE INDEX idx_incidents_started_at ON incidents(started_at DESC);
CREATE INDEX idx_incidents_service_status ON incidents(service_id, status);
CREATE INDEX idx_incidents_service_started_at ON incidents(service_id, started_at DESC);

-- PostgreSQL partial unique index ensuring single active incident per service & type
CREATE UNIQUE INDEX uk_incidents_service_type_open ON incidents(service_id, incident_type) WHERE status = 'OPEN';
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

## Incident Detection & Recovery Engine

PulseGuard features an automated Incident Detection & Recovery Engine that continuously evaluates real health-check probe results (from both manual requests and automated scheduler polling cycles), tracks incident lifecycles, and detects recovery:

```
       Consecutive Failures >= Threshold (default: 3)
   [HEALTHY] ───────────────────────────────────────────► [OPEN Incident]
      ▲                                                         │
      │                                                         │ Subsequent Failures
      │                                                         ▼
      │                                                 [Occurrence Update]
      │                                                 (Increments count,
      │                                                  updates lastOccurrenceAt)
      │                                                         │
      │         Consecutive Successes >= Threshold (default: 1) │
      └─────────────────────────────────────────────────────────┘
                            [RESOLVED Incident]
                   (Sets resolvedAt, details, preserves history)
```

### 1. Incident Lifecycle & Transition Rules

- **Detection Threshold**: An incident of type `SERVICE_UNAVAILABLE` with severity `CRITICAL` is generated only when a service experiences `N` consecutive failures (configurable via `pulseguard.incident.consecutive-failures-threshold`, default `3`). Isolated intermittent failures do not generate false-positive alarms.
- **Occurrence Updating**: When a service with an already `OPEN` incident continues to fail, the engine records an occurrence update—incrementing `occurrenceCount` and advancing `lastOccurrenceAt`—instead of generating duplicate incidents.
- **Streak Resetting**: A successful check resets the consecutive-failure counter. For example, 2 failures followed by 1 success and 1 failure results in a failure count of 1.
- **Automated Recovery**: When an open incident exists and the service achieves `M` consecutive successful checks (configurable via `pulseguard.incident.consecutive-successes-threshold`, default `1`), the incident transitions to `RESOLVED` status, timestamps `resolvedAt`, and records resolution details. Subsequent successful checks do not trigger duplicate resolutions.
- **Preserved History**: Resolved incidents remain permanently persisted and queryable for retrospective SLA tracking.

### 2. Concurrency & Duplicate Prevention

To ensure database correctness in multi-threaded and distributed environments:
1. **Row-Level Pessimistic Locking**: `HealthCheckExecutionServiceImpl` acquires a pessimistic write lock (`SELECT ... FOR UPDATE`) on the monitored service row before recording health checks and evaluating incident rules. All checks for the same service execute sequentially with transactional isolation, guaranteeing atomic failure counts and preventing race conditions.
2. **PostgreSQL Partial Unique Constraint**: The database schema enforces `CREATE UNIQUE INDEX uk_incidents_service_type_open ON incidents(service_id, incident_type) WHERE status = 'OPEN'`. If concurrent requests ever bypassed application synchronization, PostgreSQL rejects duplicate open incidents.
3. **Graceful Fallback**: If a duplicate key violation is encountered during an insert race, the transaction safely catches the violation and falls back to recording an occurrence on the existing incident.

### 3. Incident Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `pulseguard.incident.consecutive-failures-threshold` | `3` | Consecutive failures required before opening an incident |
| `pulseguard.incident.consecutive-successes-threshold` | `1` | Consecutive successful checks required to resolve an open incident |
| `pulseguard.incident.initial-severity` | `CRITICAL` | Initial severity assigned to confirmed service outages (`WARNING`, `CRITICAL`) |

### 4. Metrics & Observability

Thread-safe counters and Micrometer metrics track incident activity:
- `pulseguard.incidents.created`: Total number of incidents created
- `pulseguard.incidents.resolved`: Total number of incidents resolved
- `pulseguard.incidents.occurrences`: Total number of incident occurrences updated
- `pulseguard.incidents.failures`: Total count of incident evaluation processing failures
- `pulseguard.incidents.open`: Gauge tracking current number of open incidents

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

### Incident Management (`/api/v1/incidents`)

#### 9. Retrieve Service Incident History
* **Endpoint**: `GET /api/v1/services/{serviceId}/incidents`
* **Query Parameters**:
  * `page` (optional, int, default `0`): Page index
  * `size` (optional, int, default `20`, max `100`): Page size
  * `sort` (optional, string, default `startedAt,desc`): Sort order
* **Status**: `200 OK`

#### 10. List Global Incidents with Filters
* **Endpoint**: `GET /api/v1/incidents`
* **Query Parameters**:
  * `serviceId` (optional, UUID): Filter by service ID
  * `status` (optional, string): Filter by status (`OPEN`, `RESOLVED`)
  * `severity` (optional, string): Filter by severity (`WARNING`, `CRITICAL`)
  * `type` (optional, string): Filter by type (`SERVICE_UNAVAILABLE`, `HIGH_LATENCY`, `HIGH_ERROR_RATE`)
  * `from` (optional, ISO-8601 timestamp): Filter incidents started after this timestamp
  * `to` (optional, ISO-8601 timestamp): Filter incidents started before this timestamp
  * `page` (optional, int, default `0`): Page index
  * `size` (optional, int, default `20`, max `100`): Page size
  * `sort` (optional, string, default `startedAt,desc`): Sort order
* **Status**: `200 OK`

**Response Body**:
```json
{
  "content": [
    {
      "id": "e6fbbd42-2d93-4a18-80f0-c5a5e3052140",
      "serviceId": "c56a4180-65aa-42ec-a945-5fd21dec0538",
      "serviceName": "Payment Gateway API",
      "incidentType": "SERVICE_UNAVAILABLE",
      "severity": "CRITICAL",
      "status": "OPEN",
      "summary": "Service 'Payment Gateway API' unavailable: 3 consecutive health check failures",
      "details": "Connection refused to upstream host",
      "startedAt": "2026-10-10T14:20:00Z",
      "lastOccurrenceAt": "2026-10-10T14:21:30Z",
      "resolvedAt": null,
      "occurrenceCount": 4,
      "createdAt": "2026-10-10T14:21:00Z",
      "updatedAt": "2026-10-10T14:21:30Z"
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

#### 11. List Active / Open Incidents
* **Endpoint**: `GET /api/v1/incidents/open`
* **Status**: `200 OK`

#### 12. Get Incident by ID
* **Endpoint**: `GET /api/v1/incidents/{id}`
* **Status**: `200 OK` (or `404 Not Found`)

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

* **Domain Unit Tests** (`MonitoredServiceTest`, `HealthCheckTest`, `IncidentTest`): Validates entity lifecycle, state mutations, clamping, and invariant enforcement.
* **Prober Unit Tests** (`JavaHttpHealthProberTest`): Validates HTTP probing, latency measurement, timeout handling, and connection error handling using an embedded JDK `HttpServer`.
* **Scheduler Unit Tests** (`DueCheckEvaluatorTest`, `HealthCheckSchedulerTest`): Validates dynamic due evaluation across unverified/verified services, time interval boundaries, overlap protection, and executor capacity rejection.
* **Scheduler Concurrency & Lifecycle Tests** (`SchedulerConcurrencyTest`, `SchedulerLifecycleIntegrationTest`): Tests parallel multi-service execution, slow-service non-blocking guarantees via synchronization latches, and Spring Boot application lifecycle startup/shutdown.
* **Incident Detection & Recovery Tests** (`IncidentDetectionServiceTest`): Tests threshold-based incident creation, occurrence counter updates on repeated failures, streak resets on intermediate successes, automatic recovery resolution, and exception isolation.
* **Incident Concurrency & Lifecycle Tests** (`IncidentConcurrencyTest`, `IncidentLifecycleIntegrationTest`): Tests concurrent health checks preventing duplicate open incidents via pessimistic locking and database constraints, and end-to-end failure -> incident -> recovery -> API query.
* **Service Layer Unit Tests** (`ServiceManagementServiceTest`, `HealthCheckExecutionServiceTest`): Tests business logic, unique constraints, health check execution, and repository interactions using Mockito.
* **Controller Slice Tests** (`ServiceControllerTest`, `HealthCheckControllerTest`, `IncidentControllerTest`): Validates HTTP contracts, Bean Validation rules, query filters, JSON serialization, and RFC 7807 error mappings using `MockMvc`.
* **Repository Slice Tests** (`ServiceRepositoryTest`, `HealthCheckRepositoryTest`, `IncidentRepositoryTest`): Tests JPA mappings, Flyway migrations (`V1`, `V2`, `V3`), historical ordering, dynamic criteria specifications, group aggregation queries, and cascade deletes with `@DataJpaTest`.
* **PostgreSQL Testcontainers** (`PulseGuardPostgresTestcontainersIntegrationTest`): Runs end-to-end against real PostgreSQL when a Docker daemon is available.

---

## Phase 5 — Notification & Alerting Dispatcher Engine

PulseGuard incorporates a production-grade, highly reliable alerting and notification dispatcher engine that reacts to incident lifecycle events (`INCIDENT_OPENED`, `INCIDENT_OCCURRENCE`, `INCIDENT_RESOLVED`).

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       Incident State Transition                             │
│                  (Opened / Occurrence / Resolved)                           │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Enqueue in same DB transaction
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                   Transactional Outbox (notification_outbox)                │
│  - One snapshot row per enabled channel                                     │
│  - Unique constraint (event_id, channel) guarantees idempotency             │
│  - Initial state: PENDING                                                   │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ Atomic CAS Claim (Lease Token)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                 Background Dispatcher (NotificationDispatcher)              │
│  - Periodic poll, bounded ThreadPoolTaskExecutor (pg-notify-)               │
│  - Per-channel Token-Bucket Rate Limiter                                    │
│  - State: PROCESSING (lease_expires_at)                                      │
└──────────────────────┬──────────────────────────────┬───────────────────────┘
                       │                              │
                       ▼                              ▼
      ┌────────────────────────────────┐  ┌──────────────────────────────────┐
      │   WebhookNotificationChannel   │  │     SlackNotificationChannel     │
      │ - Signed HMAC-SHA256 payload   │  │ - Slack Incoming Webhook         │
      │ - Idempotency-Key header       │  │ - Escaped mrkdwn text & icons    │
      └────────────────┬───────────────┘  └──────────────────┬───────────────┘
                       │                                     │
                       └──────────────────────┬──────────────┘
                                              │ SSRF Defense & Pinned IP Connect
                                              ▼
                        ┌──────────────────────────────────────────┐
                        │          SafeSocketHttpClient            │
                        │ - DNS resolved once, all IPs validated   │
                        │ - Blocks RFC1918, loopback, link-local   │
                        │ - HTTPS enforcement & SNI verification   │
                        │ - Redirects never followed               │
                        └─────────────────────┬────────────────────┘
                                              │
                      ┌───────────────────────┴───────────────────────┐
                      ▼                                               ▼
         [ Success (2xx) ]                               [ Failure (4xx / 5xx / Network) ]
                 │                                                    │
                 ▼                                                    ▼
    State: DELIVERED                                     Permanent? (4xx / Policy Violation)
    Recorded in delivery_attempts                                     │
                                                     ┌────────────────┴────────────────┐
                                                     ▼                                 ▼
                                                Yes: DEAD                    No: Retryable (5xx, 429)
                                                                                       │
                                                                           Attempt < Max Attempts?
                                                                             ├── Yes: State PENDING
                                                                             │   (Exponential Backoff + Jitter)
                                                                             └── No: State DEAD
```

### 1. Delivery Guarantees & Transactional Outbox
* **At-Least-Once Delivery**: To survive process crashes and network partitions, outbox records are enqueued inside the same database transaction as the incident state change.
* **Lease-Based Worker Safety**: Workers claim batches using an atomic compare-and-set conditional update (`UPDATE notification_outbox SET status='PROCESSING', lease_token=?, lease_expires_at=? WHERE status='PENDING' OR (status='PROCESSING' AND lease_expires_at < now)`). If a worker crashes mid-delivery, the expired lease allows other workers to reclaim the item after `lease_duration`.
* **Idempotency Keys**: Each outbox item carries a deterministic UUID `event_id` (`incident:{id}:OPENED`, `incident:{id}:RESOLVED`, `incident:{id}:OCCURRENCE:{count}`). Webhook receivers receive this in the `Idempotency-Key` header, allowing consumers to deduplicate redeliveries.
* **No Real-Time Network I/O in Transactions**: The incident detection transaction only inserts database rows. Actual HTTP delivery occurs asynchronously in background dispatcher threads.

### 2. Destination Security & SSRF Defense
* **SafeSocketHttpClient**: Standard Java `HttpClient` re-resolves DNS at connect time, leaving a TOCTOU DNS-rebinding window. PulseGuard features a custom socket HTTP client that resolves DNS once, validates **every** resolved IP against blocked CIDR ranges, and connects directly to the validated IP while preserving TLS SNI and hostname verification.
* **Blocked IP Ranges**:
  - IPv4: Loopback (`127.0.0.0/8`), Private RFC1918 (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`), Link-Local (`169.254.0.0/16`), CGNAT (`100.64.0.0/10`), Multicast, Reserved (`240.0.0.0/4`), Broadcast (`255.255.255.255`), Documentation (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`), Benchmarking (`198.18.0.0/15`).
  - IPv6: Loopback (`::1`), Link-Local (`fe80::/10`), ULA (`fc00::/7`), NAT64 (`64:ff9b::/96`), 6to4 (`2002::/16`), Teredo (`2001::/32`), Documentation (`2001:db8::/32`).
* **Scheme & Host Protections**: Only `https` destinations are allowed by default (`http` is strictly blocked unless `pulseguard.notifications.security.allow-unsafe-destinations=true` in local development). User credentials in URLs are blocked.
* **Slack Host Restriction**: The Slack channel requires destination hosts to match `hooks.slack.com` or `hooks.slack-gov.com`.
* **No Redirects Followed**: HTTP 3xx responses are treated as permanent delivery failures rather than followed automatically.
* **Secret Redaction**: URLs, Slack webhook tokens, HMAC secrets, Bearer tokens, and sensitive JDK network exception messages are scrubbed by `Redactor` prior to persistence or logging.

### 3. Webhook Contract & Signature Verification
HTTP POST headers sent to generic webhook endpoints:
* `Content-Type: application/json; charset=utf-8`
* `User-Agent: PulseGuard-Webhook/1`
* `X-PulseGuard-Event: INCIDENT_OPENED | INCIDENT_OCCURRENCE | INCIDENT_RESOLVED`
* `X-PulseGuard-Delivery: <delivery-uuid>`
* `Idempotency-Key: <event-uuid>`
* `X-PulseGuard-Timestamp: <unix-timestamp>`
* `X-PulseGuard-Signature: sha256=<hex-hmac-sha256>`

**Example Webhook Payload**:
```json
{
  "schemaVersion": 1,
  "eventId": "f5e9d997-c81f-3610-863a-bb09aa903020",
  "deliveryId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType": "INCIDENT_OPENED",
  "occurredAt": "2026-10-10T12:00:00Z",
  "service": {
    "id": "e674a2d8-2b81-4fe6-a947-f7cb12999e5a",
    "name": "Payments API"
  },
  "incident": {
    "id": "bb912803-b097-4022-9442-8809489aa455",
    "type": "SERVICE_UNAVAILABLE",
    "severity": "CRITICAL",
    "status": "OPEN",
    "summary": "Service 'Payments API' unavailable: 3 consecutive health check failures",
    "details": "Connection timed out after 3000ms",
    "startedAt": "2026-10-10T11:58:30Z",
    "lastOccurrenceAt": "2026-10-10T12:00:00Z",
    "resolvedAt": null,
    "occurrenceCount": 3
  }
}
```

**Verifying the HMAC Signature (Java snippet)**:
```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
mac.update(timestamp.getBytes(StandardCharsets.UTF_8));
mac.update((byte) '.');
String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal(bodyBytes));
boolean valid = MessageDigest.isEqual(expected.getBytes(), signatureHeader.getBytes());
```

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
    check_interval_seconds INTEGER NOT NULL,
    timeout_ms INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Phase 2 — Health Checks (`V2__create_health_checks_table.sql`)
```sql
CREATE TABLE IF NOT EXISTS health_checks (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    result VARCHAR(30) NOT NULL,
    http_status_code INTEGER,
    response_time_ms BIGINT NOT NULL,
    error_message VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Phase 3 & 4 — Incidents (`V3__create_incidents_table.sql`)
```sql
CREATE TABLE IF NOT EXISTS incidents (
    id UUID PRIMARY KEY,
    service_id UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    incident_type VARCHAR(50) NOT NULL,
    severity VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    summary VARCHAR(255) NOT NULL,
    details VARCHAR(2000),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_occurrence_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Phase 5 — Notification Outbox (`V4__create_notification_outbox.sql`)
```sql
CREATE TABLE IF NOT EXISTS notification_outbox (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL,
    channel VARCHAR(30) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    incident_id UUID NOT NULL,
    service_id UUID NOT NULL,
    payload VARCHAR(4000) NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
    lease_token VARCHAR(64),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(1000),
    last_http_status INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_notification_outbox_event_channel UNIQUE (event_id, channel)
);

CREATE TABLE IF NOT EXISTS notification_delivery_attempts (
    id UUID PRIMARY KEY,
    outbox_id UUID NOT NULL REFERENCES notification_outbox(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL,
    attempted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    duration_ms BIGINT NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    http_status INTEGER,
    error_message VARCHAR(1000),
    CONSTRAINT uk_notification_attempts_outbox_number UNIQUE (outbox_id, attempt_number)
);
```

---

## Notification Management API

### List Outbox Notifications
`GET /api/v1/notifications?status=DELIVERED&channel=WEBHOOK&page=0&size=20`

Response:
```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eventId": "f5e9d997-c81f-3610-863a-bb09aa903020",
      "channel": "WEBHOOK",
      "eventType": "INCIDENT_OPENED",
      "incidentId": "bb912803-b097-4022-9442-8809489aa455",
      "serviceId": "e674a2d8-2b81-4fe6-a947-f7cb12999e5a",
      "status": "DELIVERED",
      "attemptCount": 1,
      "nextAttemptAt": "2026-10-10T12:00:00Z",
      "lastError": null,
      "lastHttpStatus": 200,
      "createdAt": "2026-10-10T12:00:00Z",
      "updatedAt": "2026-10-10T12:00:01Z",
      "deliveredAt": "2026-10-10T12:00:01Z"
    }
  ],
  "totalElements": 1,
  "totalPages": 1
}
```

### Inspect Notification Detail & Delivery Attempts
`GET /api/v1/notifications/{id}`

Response:
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventId": "f5e9d997-c81f-3610-863a-bb09aa903020",
  "channel": "WEBHOOK",
  "eventType": "INCIDENT_OPENED",
  "incidentId": "bb912803-b097-4022-9442-8809489aa455",
  "serviceId": "e674a2d8-2b81-4fe6-a947-f7cb12999e5a",
  "status": "DELIVERED",
  "attemptCount": 1,
  "nextAttemptAt": "2026-10-10T12:00:00Z",
  "lastError": null,
  "lastHttpStatus": 200,
  "createdAt": "2026-10-10T12:00:00Z",
  "updatedAt": "2026-10-10T12:00:01Z",
  "deliveredAt": "2026-10-10T12:00:01Z",
  "attempts": [
    {
      "id": "99277dca-7182-4422-91f1-3fcf7b1933a2",
      "attemptNumber": 1,
      "attemptedAt": "2026-10-10T12:00:00.500Z",
      "durationMs": 142,
      "outcome": "SUCCESS",
      "httpStatus": 200,
      "errorMessage": null
    }
  ]
}
```

---

## Known Limitations

- **At-Least-Once Delivery & Duplicate Window**: If a destination accepts an alert but the connection terminates before the HTTP response code is read by PulseGuard, or if a slow delivery worker exceeds its lease duration, PulseGuard may redeliver the event. Consumers should use `Idempotency-Key` or `eventId` to achieve idempotency. Note that Slack Incoming Webhooks do not natively support idempotency keys.
- **In-Memory Rate Limiting**: The token-bucket rate limiter runs in-memory per application instance. Across N horizontal instances, total dispatch rate is bounded by N times the configured per-instance limit.
- **SSRF Restrictions on Health Prober**: While outbound notification webhooks are strictly guarded by `SafeSocketHttpClient` and SSRF CIDR policies, the monitored service health checker (`JavaHttpHealthProber`) currently probes arbitrary user-registered URLs without private-network restrictions to allow internal intranet monitoring.
- **No Native Email or PagerDuty**: Email (SMTP/SES) and PagerDuty (Events API v2) can be cleanly plugged in via the `NotificationChannel` interface, but are not bundled by default to avoid heavy third-party SDK dependencies.

---

## Roadmap

* **Phase 1 (Completed)**: Core service registry foundation, PostgreSQL + Flyway persistence, layered architecture, DTO isolation, Bean Validation, Global Exception Handling, and comprehensive test suite.
* **Phase 2 (Completed)**: Health Check Execution Engine with standard Java 21 `HttpClient`, latency measurement, status code capturing, manual check trigger (`POST /api/v1/services/{id}/checks`), historical checks pagination (`GET /api/v1/services/{id}/checks`), and Flyway `V2` migration.
* **Phase 3 (Completed)**: Automated background scheduling engine with bounded `ThreadPoolTaskExecutor`, independent per-service check intervals, same-service overlap protection, failure isolation, graceful shutdown, and scheduler Micrometer metrics.
* **Phase 4 (Completed)**: Incident Detection & Recovery Engine, deterministic consecutive-failure thresholds, automated recovery resolution, occurrence counters, pessimistic row locking and database partial unique constraint against concurrent duplicate incidents, incident history & filtering REST APIs (`/api/v1/incidents`), and Micrometer observability.
* **Phase 5 (Completed)**: Alerting & Notification Dispatcher Engine, Transactional Outbox pattern, lease-based concurrent claiming, bounded exponential backoff with jitter, SSRF and DNS-rebinding defense via pinned-IP socket client, Generic Signed Webhook channel, Slack channel, token-bucket rate limiting, management audit APIs (`/api/v1/notifications`), and Micrometer observability.
* **Phase 6 (Proposed)**: Statistical Anomaly & Latency Drift Detection — dynamic percentile-based latency thresholds ($p95$, $p99$), moving-average error rate spike detection, automated incident severity escalation (WARNING -> CRITICAL), and authenticated notification administration.

