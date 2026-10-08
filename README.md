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

## Database Schema (Phase 1)

Flyway migration script: `src/main/resources/db/migration/V1__create_services_table.sql`

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

---

## REST API Specification

Base URI: `/api/v1/services`

### 1. Register Monitored Service
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

### 2. List Monitored Services
* **Endpoint**: `GET /api/v1/services`
* **Query Parameters**:
  * `enabled` (optional, boolean): Filter by active status (`true` / `false`)
  * `status` (optional, string): Filter by health status (`UNKNOWN`, `HEALTHY`, `DEGRADED`, `UNHEALTHY`)
  * `page` (optional, int, default `0`): Page index
  * `size` (optional, int, default `20`): Page size
  * `sort` (optional, string, default `createdAt,desc`): Sort property and direction
* **Status**: `200 OK`

### 3. Get Service by ID
* **Endpoint**: `GET /api/v1/services/{id}`
* **Status**: `200 OK` (or `404 Not Found`)

### 4. Update Service Configuration
* **Endpoint**: `PUT /api/v1/services/{id}`
* **Status**: `200 OK`

**Request Body**:
```json
{
  "name": "Payment Gateway API (v2)",
  "description": "Updated cluster configuration",
  "url": "https://api-v2.payment.internal/health",
  "checkIntervalSeconds": 15,
  "timeoutMs": 2500
}
```

### 5. Toggle Service Enabled Status
* **Endpoint**: `PATCH /api/v1/services/{id}/status`
* **Status**: `200 OK`

**Request Body**:
```json
{
  "enabled": false
}
```

### 6. Delete Monitored Service
* **Endpoint**: `DELETE /api/v1/services/{id}`
* **Status**: `204 No Content` (or `404 Not Found`)

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
    },
    {
      "field": "checkIntervalSeconds",
      "rejectedValue": 2,
      "message": "Check interval must be at least 5 seconds"
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

Verify PostgreSQL is running:
```bash
docker compose ps
```

### 2. Configure Environment Variables

Copy `.env.example` to `.env` or set environment variables:
```bash
cp .env.example .env
```

Default local database connection values:
- **Host**: `localhost`
- **Port**: `5432`
- **Database**: `pulseguard`
- **Username**: `pulseguard_user`
- **Password**: `pulseguard_secret_change_in_production`

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

* **Domain Unit Tests** (`MonitoredServiceTest`): Validates entity lifecycle, state mutations, and invariant enforcement.
* **Service Layer Unit Tests** (`ServiceManagementServiceTest`): Tests business logic, unique constraints, and repository interactions using Mockito.
* **Controller Slice Tests** (`ServiceControllerTest`): Validates HTTP contract, Bean Validation rules, JSON serialization, and error mapping using `MockMvc`.
* **Repository Slice Tests** (`ServiceRepositoryTest`): Tests JPA mappings, Flyway migrations, and custom query derivation with `@DataJpaTest`.
* **PostgreSQL Testcontainers** (`PulseGuardPostgresTestcontainersIntegrationTest`): Runs end-to-end against real PostgreSQL when a Docker daemon is available.

---

## Roadmap

* **Phase 1 (Completed)**: Core service registry foundation, PostgreSQL + Flyway persistence, layered architecture, DTO isolation, Bean Validation, Global Exception Handling, and comprehensive test suite.
* **Phase 2**: Non-blocking asynchronous health check execution engine, latency tracking, HTTP status recording, and manual triggers (`/api/v1/services/{id}/checks`).
* **Phase 3**: Automated background scheduler, incident generation rules, recovery detection, and incident resolution lifecycles (`/api/v1/services/{id}/incidents`).
* **Phase 4**: Anomaly detection algorithms, alerting channels (Slack, Webhooks, Email), and observability dashboards.
