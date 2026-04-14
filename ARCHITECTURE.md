# Architecture: sosialhjelp-upload

## Purpose

`sosialhjelp-upload` is a backend service for NAV's digital social assistance applications ([sosialhjelp-innsyn](https://github.com/navikt/sosialhjelp-innsyn) and [sosialhjelp-soknad](https://github.com/navikt/sosialhjelp-soknad)). It enables citizens to upload supporting documents (attachments / _ettersendelser_) as part of their social assistance case.

The service handles the full lifecycle of a file upload:
1. Receives chunked file uploads from the browser using the [TUS resumable upload protocol](https://tus.io/)
2. Validates files (type, size, virus scan, PDF integrity)
3. Converts non-PDF/image documents to PDF via Gotenberg
4. Encrypts files using CMS (Cryptographic Message Syntax) with the recipient's public key
5. Stores encrypted files temporarily in KS Fiks mellomlagring
6. Submits a bundle of uploaded files (_ettersendelse_) to the KS Fiks API
7. Streams real-time upload status to connected clients via Server-Sent Events (SSE)

---

## Technology Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| Server framework | [Ktor](https://ktor.io/) (Netty engine) |
| Dependency injection | Ktor built-in DI (`ktor-server-di`) |
| Database | PostgreSQL |
| Query builder | [JOOQ](https://www.jooq.org/) (type-safe SQL, generated classes committed) |
| DB migrations | [Flyway](https://flywaydb.org/) |
| File type detection | [Apache Tika](https://tika.apache.org/) |
| PDF validation | [Apache PDFBox](https://pdfbox.apache.org/) |
| PDF generation | Internal (`EttersendelsePdfGenerator`) using iText/font resources |
| Encryption | [ks-kryptering](https://github.com/ks-no/ks-kryptering) — CMS/PKCS#7 via BouncyCastle |
| Metrics | [Micrometer](https://micrometer.io/) + Prometheus |
| Async | Kotlin Coroutines |
| HTTP client | Ktor CIO client |
| Serialization | kotlinx.serialization + Jackson (for Fiks API) |

---

## External Integrations

```mermaid
graph LR
    Browser["Browser\n(sosialhjelp-innsyn\n/ sosialhjelp-soknad)"]
    Wonderwall["Wonderwall\n(ID-porten sidecar)"]
    App["sosialhjelp-upload"]
    Postgres["PostgreSQL"]
    Gotenberg["Gotenberg\n(PDF conversion)"]
    ClamAV["ClamAV\n(virus scanner)"]
    Texas["NAIS Texas\n(Maskinporten token)"]
    Mellomlagring["KS Fiks\nMellomlagring\n(temp file storage)"]
    FiksAPI["KS Fiks API\n(ettersendelse)"]

    Browser -->|"TUS upload\n+ SSE"| Wonderwall
    Wonderwall -->|"JWT (ID-porten)"| App
    App <-->|"JOOQ / LISTEN+NOTIFY"| Postgres
    App -->|"Convert to PDF"| Gotenberg
    App -->|"Virus scan"| ClamAV
    App -->|"Get Maskinporten token"| Texas
    App -->|"Store encrypted file"| Mellomlagring
    App -->|"Submit ettersendelse"| FiksAPI
    App -->|"Fetch public key"| FiksAPI
```

| Integration | Purpose | Auth |
|---|---|---|
| **ID-porten** (via Wonderwall) | JWT auth for all user-facing routes | Bearer token (validated against JWKS) |
| **KS Fiks mellomlagring** | Temporary encrypted file storage before final submission | Maskinporten token (via Texas) |
| **KS Fiks API** | Final submission of ettersendelse; also provides the encryption public key | Maskinporten token (via Texas) + integrasjonId/passord |
| **Gotenberg** | Converts Office/text documents to PDF | None (internal) |
| **ClamAV** | Virus scanning of uploaded file bytes | None (internal) |
| **NAIS Texas** | Acquires Maskinporten tokens scoped to `ks:fiks` | NAIS workload identity |

---

## Core Domain Concepts

- **Submission** — a named group of uploads tied to a single case. Maps to the `submission` DB table. Identified externally by `externalId` (from the frontend). Gets assigned a `navEksternRefId` which references the submission at Fiks.
- **Upload** — an individual file within a submission. Progresses through states: `PENDING` → `PROCESSING` → `COMPLETE` / `FAILED`. Maps to the `upload` DB table.

---

## Database Schema

```mermaid
erDiagram
    submission {
        uuid id PK
        varchar external_id
        char owner_ident "National ID number (11 chars)"
        varchar nav_ekstern_ref_id
        timestamp created_at
    }
    upload {
        uuid id PK
        uuid submission_id FK
        varchar original_filename
        varchar mellomlagring_filename
        uuid fil_id "ID in mellomlagring"
        bigint size
        bigint mellomlagring_storrelse
        bytea chunk_data "Cleared after processing"
        varchar status
        bigint chunk_offset
        varchar sha512
        timestamp updated_at
        boolean processing_claimed
    }
    error {
        uuid id PK
        uuid upload FK
        text code
    }

    submission ||--o{ upload : "contains"
    upload ||--o{ error : "has"
```

Migrations live in `src/main/resources/db/migration/` following `V{major}.{minor}__{description}.sql`. JOOQ-generated classes are committed under `database/generated/` and must be regenerated (`./gradlew generateJooq`) when the schema changes.

---

## Upload Lifecycle

```mermaid
sequenceDiagram
    participant Browser
    participant TusRoutes
    participant TusUploadService
    participant UploadValidator
    participant GotenbergService
    participant EncryptionService
    participant MellomlagringClient
    participant DB

    Browser->>TusRoutes: POST /tus/files (create)
    TusRoutes->>TusUploadService: create(contextId, filename, size, personident)
    TusUploadService->>DB: getOrCreate submission, insert upload record
    TusRoutes-->>Browser: 201 Created (Location: /tus/files/{id})

    loop PATCH chunks
        Browser->>TusRoutes: PATCH /tus/files/{id}
        TusRoutes->>TusUploadService: appendChunk(uploadId, offset, data)
        TusUploadService->>DB: append chunk bytes, update offset
        TusRoutes-->>Browser: 204 No Content (Upload-Offset)
    end

    Note over TusUploadService: Final chunk triggers post-processing

    TusUploadService->>UploadValidator: validate(filename, data, size)
    Note over UploadValidator: Tika (MIME), size, filename,<br/>PDFBox (encrypted PDF),<br/>ClamAV (virus)
    alt Validation failed
        TusUploadService->>DB: addErrors, clearChunkData, notifyChange
    else Validation passed
        TusUploadService->>GotenbergService: convertToPdf (if not PDF/image)
        TusUploadService->>EncryptionService: encryptBytes
        TusUploadService->>MellomlagringClient: uploadFile → filId
        TusUploadService->>DB: setFilId, clearChunkData, notifyChange
    end
```

---

## Submission Flow

Once all files are uploaded and the user triggers submission:

```mermaid
sequenceDiagram
    participant Browser
    participant ActionRoutes
    participant EttersendelseService
    participant FiksClient
    participant EttersendelsePdfGenerator
    participant MellomlagringClient
    participant EncryptionService
    participant DB

    Browser->>ActionRoutes: POST /submission/{submissionId}/submit
    ActionRoutes->>EttersendelseService: upload(metadata, fiksDigisosId, token, submissionId)

    EttersendelseService->>FiksClient: getSak(fiksDigisosId) → kommunenummer
    EttersendelseService->>DB: getNavEksternRefId, getUploads

    EttersendelseService->>EttersendelsePdfGenerator: generate(metadata, uploads)
    EttersendelseService->>EncryptionService: encryptBytes(ettersendelsePdf)
    EttersendelseService->>MellomlagringClient: uploadFile(ettersendelse.pdf)

    EttersendelseService->>FiksClient: uploadEttersendelse(fiksDigisosId, filer[])
    FiksClient-->>EttersendelseService: HTTP response

    alt Success
        EttersendelseService->>DB: cleanup(submissionId)
        EttersendelseService->>SubmissionNotificationService: notifyUpdate
        ActionRoutes-->>Browser: 200 OK
    else Failure
        ActionRoutes-->>Browser: 500 / error
    end
```

---

## Real-time Status Streaming (SSE)

Upload status is pushed to connected clients without polling, using Postgres `LISTEN/NOTIFY` as a lightweight message bus.

```mermaid
sequenceDiagram
    participant Browser
    participant StatusRoutes
    participant SubmissionNotificationService
    participant DB

    Browser->>StatusRoutes: GET /status/{id} (SSE)
    StatusRoutes->>StatusRoutes: send initial submission state
    StatusRoutes->>SubmissionNotificationService: getSubmissionFlow(submissionId)

    Note over SubmissionNotificationService: Single shared LISTEN connection<br/>polls Postgres every 500ms

    loop On each DB change
        DB-->>SubmissionNotificationService: NOTIFY submission_update
        SubmissionNotificationService->>StatusRoutes: emit submissionId
        StatusRoutes->>Browser: SSE event (updated SubmissionState)
    end

    Note over StatusRoutes: Heartbeat every 10s to keep connection alive
```

A single long-lived Postgres connection listens on the `submission_update` channel. Change events are fanned out to all active SSE subscribers via a `SharedFlow`, so the number of DB connections does not grow with the number of connected clients.

---

## Security

```mermaid
flowchart TD
    A[Incoming Request] --> B{"/internal/* ?"}
    B -->|Yes| C["No auth required<br/>e.g. /internal/isAlive"]
    B -->|No| D["Wonderwall injects<br/>ID-porten JWT"]
    D --> E{"JWT valid?<br/>client_id audience?<br/>acr = idporten-loa-high?"}
    E -->|No| F[401 Unauthorized]
    E -->|Yes| G{"Route has /{id}<br/>or /{submissionId}?"}
    G -->|"/{id}"| H["verifyUploadOwnership plugin<br/>checks upload.submission.owner_ident == JWT sub"]
    G -->|"/{submissionId}"| I["verifySubmissionOwnership plugin<br/>checks submission.owner_ident == JWT sub"]
    H -->|Mismatch| J[404 Not Found]
    I -->|Mismatch| J
    H -->|Match| K[Route handler]
    I -->|Match| K
    G -->|No param| K
```

- All routes under `/sosialhjelp/upload/` require a valid ID-porten JWT injected by the Wonderwall sidecar.
- JWT is validated in `Security.kt`: audience, issuer, and `acr=idporten-loa-high`.
- Ownership is enforced by Ktor route-scoped plugins in `OwnershipInterceptors.kt`. On failure the response is `404 Not Found` (not 403) to avoid leaking resource existence.
- The JWT `sub` claim is used as `personident` throughout.

---

## Background Services

Two background coroutines run continuously on application start:

| Service | Interval | Purpose |
|---|---|---|
| `UploadRecoveryService` | 1 minute | Re-processes uploads stuck in `PROCESSING` state (e.g. after a crash mid-flight) |
| `RetentionService` | 1 minute | Deletes stale submissions and uploads that were never submitted |

---

## Package Structure

```
no.nav.sosialhjelp.upload
├── action/          # EttersendelseService, FiksClient, MellomlagringClient, EncryptionService
├── database/        # JOOQ repositories, Flyway, SubmissionNotificationService (LISTEN/NOTIFY)
│   ├── generated/   # JOOQ-generated table/record classes (committed, do not edit)
│   └── notify/      # Postgres LISTEN/NOTIFY → SharedFlow fan-out
├── documents/       # GET /upload/{uploadId} — retrieves a file from mellomlagring
├── pdf/             # GotenbergService (conversion), EttersendelsePdfGenerator
├── status/          # SSE /status/{id}, SubmissionService
├── texas/           # TexasClient — Maskinporten token via NAIS Texas
├── tus/             # TUS protocol routes and TusUploadService
└── validation/      # UploadValidator (Tika, PDFBox, ClamAV, size, filename)
```

---

## Local Development

```bash
# Start local dependencies (Postgres on :54322, Gotenberg on :3010)
docker compose up -d

# Run with development mode (mock encryption, mock Fiks)
./gradlew run -Pdevelopment

# Run tests (requires Docker for Testcontainers/Postgres)
./gradlew test

# Regenerate JOOQ classes after schema changes
./gradlew generateJooq
```

Key environment variables (see `application.yaml` for defaults):

| Variable | Purpose |
|---|---|
| `RUNTIME_ENV` | `local`/`mock` uses no-op encryption and mock Fiks |
| `POSTGRES_JDBC_URL` / `_USERNAME` / `_PASSWORD` | Database |
| `IDPORTEN_CLIENT_ID` / `_ISSUER` / `_JWKS_URI` | JWT auth |
| `GOTENBERG_URL` | PDF conversion |
| `FIKS_URL` / `INTEGRASJONSID_FIKS` / `INTEGRASJONPASSORD_FIKS` | KS Fiks |
| `CLAMAV_URL` | Virus scanner |
| `NAIS_TOKEN_ENDPOINT` | Texas Maskinporten token endpoint |
