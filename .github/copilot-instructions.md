# Copilot Instructions for sosialhjelp-upload

## Overview

`sosialhjelp-upload` is a Kotlin/Ktor file upload coordination service for NAV's social assistance apps (sosialhjelp-innsyn and sosialhjelp-soknad). It receives resumable uploads via the TUS protocol, validates and converts files to PDF via Gotenberg, stores them in Google Cloud Storage, and submits them to KS Fiks/mellomlagring. Real-time status is streamed to clients via SSE.

## Build, Test, and Run

```bash
# Run all tests (uses Testcontainers for PostgreSQL — Docker must be running)
./gradlew test

# Run a single test class
./gradlew test --tests "no.nav.sosialhjelp.upload.database.UploadRepositoryTest"

# Run a single test method
./gradlew test --tests "no.nav.sosialhjelp.upload.database.UploadRepositoryTest.test create upload"

# Build a fat jar
./gradlew build

# Start local dependencies (Postgres on :54322, Gotenberg on :3010)
docker compose up -d

# Regenerate JOOQ code (requires Postgres to be running)
./gradlew generateJooq

# Run with development mode
./gradlew run -Pdevelopment
```

Gradle requires GitHub credentials for NAV's private package registry. These must be set in `~/.gradle/gradle.properties`:
```
githubUser=<your-github-username>
githubPassword=<your-github-token>
```

In CI they are set as `ORG_GRADLE_PROJECT_githubUser` and `ORG_GRADLE_PROJECT_githubPassword`.

## Architecture

All routes are under `/sosialhjelp/upload/` and protected by JWT (via Wonderwall), except `/internal/isAlive`.

### Upload lifecycle

1. Client POSTs to `/tus/files` → `TusRoutes` → `TusUploadService.create()` creates a DB record
2. Client sends chunks via PATCH `/tus/files/{id}`
3. When the last chunk arrives, `TusUploadService.processCompletedUpload()` runs:
   - Validates with `UploadValidator` (file type via Apache Tika, size, virus scan via ClamAV)
   - Converts non-PDF/image formats to PDF via `GotenbergService`
   - Encrypts with `EncryptionService` (real CMS encryption in prod, no-op mock locally)
   - Uploads encrypted file to KS Fiks mellomlagring via `MellomlagringClient`
4. Frontend receives status updates via SSE at `/status/{documentId}` (uses `DocumentNotificationService`)
5. User submits via POST `/document/{documentId}/submit` → `DownstreamUploadService` → Fiks API

### Key packages

| Package | Responsibility |
|---------|---------------|
| `tus/` | TUS protocol (create/append/delete) and upload orchestration |
| `status/` | SSE streaming of document status |
| `documents/` | Document retrieval routes |
| `action/` | Downstream submission to Fiks; encryption service |
| `database/` | JOOQ repositories, Flyway migrations, change notifications |
| `pdf/` | Gotenberg integration for PDF conversion |
| `validation/` | File type, size, virus scanning |
| `texas/` | Maskinporten token acquisition via NAIS Texas |

## Dependency Injection

Uses Ktor's built-in DI plugin (`ktor-server-di`). All dependencies are registered in `Application.module()` with `provide<Type>` or `provide(ClassName::class)`. Consuming them in routes:

```kotlin
val myService: MyService by application.dependencies
```

When `runtimeEnv` is `local` or `mock`, `EncryptionServiceMock` is used instead of the real CMS implementation. Local runs also use the filesystem instead of GCS.

## Database

- PostgreSQL with JOOQ for type-safe queries and Flyway for migrations
- Migrations live in `src/main/resources/db/migration/` following `V{major}.{minor}__{description}.sql`
- JOOQ-generated classes are committed under `database/generated/` — regenerate with `./gradlew generateJooq` when the schema changes (requires Postgres running on `:54322`)
- All DB operations use JOOQ `Configuration`-based transactions:
  ```kotlin
  dsl.transactionResult { tx -> repository.someQuery(tx) }
  ```
- `DocumentNotificationService` uses a Kotlin `SharedFlow` to fan out DB-level change events to SSE subscribers

## Testing conventions

- JUnit 5 + `kotlin.test` + MockK
- Database integration tests use a shared `PostgresTestContainer` singleton (Testcontainers, Postgres 17) — Docker must be running
- Test names use backtick syntax: `` fun `test create upload`() ``
- `TestUtils.kt` contains shared helper functions for test setup

## Configuration

`src/main/resources/application.yaml` uses `$VAR:fallback` syntax for environment variable injection. Key variables:

| Variable | Purpose |
|----------|---------|
| `RUNTIME_ENV` | `local`/`mock`/`dev`/`prod` — controls mock vs. real encryption |
| `POSTGRES_JDBC_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD` | Database |
| `IDPORTEN_CLIENT_ID`, `IDPORTEN_ISSUER`, `IDPORTEN_JWKS_URI` | JWT auth |
| `GOTENBERG_URL` | PDF conversion service |
| `BUCKET_NAME`, `GCS_CREDENTIALS` | Google Cloud Storage |
| `FIKS_URL`, `INTEGRASJONSID_FIKS`, `INTEGRASJONPASSORD_FIKS` | KS Fiks integration |
| `CLAMAV_URL` | Virus scanning |
| `NAIS_TOKEN_ENDPOINT` | Texas Maskinporten token endpoint |

## Security

JWT validation in `Security.kt` enforces:
- `client_id` claim must match the configured audience
- `acr` claim must be `idporten-loa-high` (high security level)
- Token subject (`sub`) is used as `personident` throughout for ownership checks
