# Copilot Instructions for sosialhjelp-upload

## Overview

`sosialhjelp-upload` is a Kotlin/Ktor file upload coordination service for NAV's social assistance apps (sosialhjelp-innsyn and sosialhjelp-soknad). It receives resumable uploads via the TUS protocol, validates and converts files to PDF via Gotenberg, stores them in KS Fiks mellomlagring, and submits them to the Fiks API. Real-time status is streamed to clients via SSE.

**Core concepts:**
- **Submission** — a group of uploads sent together (maps to the `submission` DB table; `externalId` ties it to a case in the frontend)
- **Upload** — an individual file within a submission (TUS upload, maps to the `upload` DB table)

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

### Concurrent submissions

Submissions can be created and have files uploaded concurrently. The `navEksternRefId` is assigned at **upload creation time** in `TusUploadService.create()`: if the caller does not supply a `navEksternRefId`, one is derived from Fiks via `FiksClient.getNewNavEksternRefId()` and immediately stored on the submission. It is reused for all subsequent uploads on the same submission without calling Fiks again.

**The race condition and fix:** Without coordination, two uploads created in parallel for different `contextId`s but the same `fiksDigisosId` would both call `getNewNavEksternRefId()` against the same Fiks snapshot and receive the same `navEksternRefId` — because `ettersendtInfoNAV.ettersendelser` on the Fiks side only advances when an ettersendelse is actually *submitted*, not when uploads are created locally. This would cause files from both submissions to land on the same mellomlagring URL, corrupting data on the Fiks side.

The fix uses two mechanisms together:

1. **Postgres transaction-level advisory lock** (`pg_advisory_xact_lock`, keyed on `fiksDigisosId`) — serializes upload creations for the same case across all service instances.
2. **Hybrid local + remote counter** — `getNewNavEksternRefId` takes an optional `localMax: String?` (the highest `navEksternRefId` already stored locally for this `fiksDigisosId`). The counter is derived from the max of the remote Fiks state and the local DB state, so in-flight submissions that haven't been submitted to Fiks yet are accounted for.

`fiksDigisosId` is stored on the `submission` row at creation time to support the local counter query. Unused counter slots (submissions created but never submitted) are acceptable.

### Upload lifecycle

1. Client POSTs to `/tus/files` → `TusRoutes` → `TusUploadService.create()` creates a DB record
2. Client sends chunks via PATCH `/tus/files/{id}`
3. When the last chunk arrives, `TusUploadService.processCompletedUpload()` runs:
   - Validates with `UploadValidator` (file type via Apache Tika, size, virus scan via ClamAV)
   - Converts non-PDF/image formats to PDF via `GotenbergService`
   - Encrypts with `EncryptionService` (real CMS encryption in prod, no-op mock locally)
   - Uploads encrypted file to KS Fiks mellomlagring via `MellomlagringClient`
4. Frontend receives status updates via SSE at `/status/{submissionExternalId}` (uses `SubmissionNotificationService`)
5. User submits via POST `/submission/{submissionId}/submit` → `DownstreamUploadService` → Fiks API

### Key packages

| Package | Responsibility |
|---------|---------------|
| `tus/` | TUS protocol (create/append/delete) and upload orchestration |
| `status/` | SSE streaming of submission status |
| `documents/` | Upload file retrieval (`GET /upload/{uploadId}`) |
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

When `runtimeEnv` is `local` or `mock`, `EncryptionServiceMock` is used instead of the real CMS implementation.

## Database

- PostgreSQL with JOOQ for type-safe queries and Flyway for migrations
- Migrations live in `src/main/resources/db/migration/` following `V{major}.{minor}__{description}.sql`
- JOOQ-generated classes are committed under `database/generated/` — regenerate with `./gradlew generateJooq` when the schema changes (requires Postgres running on `:54322`)
- All DB operations use JOOQ `Configuration`-based transactions:
  ```kotlin
  dsl.transactionResult { tx -> repository.someQuery(tx) }
  ```
- `SubmissionNotificationService` uses a Kotlin `SharedFlow` to fan out DB-level change events to SSE subscribers

## Testing conventions

- JUnit 5 + `kotlin.test` + MockK
- Database integration tests use a shared `PostgresTestContainer` singleton (Testcontainers, Postgres 17) — Docker must be running
- Test names use backtick syntax: `` fun `test create upload`() ``
- `TestUtils.kt` contains shared helper functions for test setup

## Configuration

`src/main/resources/application.yaml` uses `$VAR:fallback` syntax for environment variable injection. Key variables:

| Variable                                                      | Purpose                                                         |
|---------------------------------------------------------------|-----------------------------------------------------------------|
| `RUNTIME_ENV`                                                 | `local`/`mock`/`dev`/`prod` — controls mock vs. real encryption |
| `POSTGRES_JDBC_URL`, `POSTGRES_USERNAME`, `POSTGRES_PASSWORD` | Database                                                        |
| `IDPORTEN_CLIENT_ID`, `IDPORTEN_ISSUER`, `IDPORTEN_JWKS_URI`  | JWT auth                                                        |
| `GOTENBERG_URL`                                               | PDF conversion service                                          |
| `FIKS_URL`, `INTEGRASJONSID_FIKS`, `INTEGRASJONPASSORD_FIKS`  | KS Fiks integration                                             |
| `CLAMAV_URL`                                                  | Virus scanning                                                  |
| `NAIS_TOKEN_ENDPOINT`                                         | Texas Maskinporten token endpoint                               |
| `CLEAN_ON_START`                                              | Whether or not to clean database on startup                     |

## Security

JWT validation in `Security.kt` enforces:
- `client_id` claim must match the configured audience
- `acr` claim must be `idporten-loa-high` (high security level)
- Token subject (`sub`) is used as `personident` throughout for ownership checks

## Ownership enforcement

Route-scoped ownership is enforced via Ktor plugins in `OwnershipInterceptors.kt`, using `createRouteScopedPlugin` + `on(AuthenticationChecked)`. Two plugins exist:

- **`verifyUploadOwnership()`** — installs on routes with `{id}` (TUS upload routes). Reads `upload.submission_id` → verifies `submission.owner_ident` matches the JWT subject.
- **`verifySubmissionOwnership()`** — installs on routes with `{submissionId}`. Verifies `submission.owner_ident` matches the JWT subject.

On success, verified IDs are stored in `call.attributes` and route handlers read from there:
```kotlin
route("/{id}") {
    verifyUploadOwnership()
    patch { val uploadId = call.attributes[VerifiedUploadId] }
}

route("/submission/{submissionId}") {
    verifySubmissionOwnership()
    post("submit") { val submissionId = call.attributes[VerifiedSubmissionId] }
}
```

On failure the pipeline short-circuits via `call.respond(HttpStatusCode.NotFound)` — the route handler never runs. **Do not perform manual ownership checks in service methods** — rely on the interceptors instead.
