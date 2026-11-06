# Upload architecture simplification

**Status:** Separate dev implementation agreed; recovery and production cutover details pending

**Scope:** Simplify shared document upload to Fiks Mellomlager

**Code baseline:** `main` at `f34715d` (submission deletion endpoint, PR #55)

**Last updated:** 2026-11-06

## Goal

Reliably transfer validated documents to Fiks Mellomlager while showing them immediately in the browser.

Reduce the amount of local state that must agree with Fiks. Make uncertain upload and deletion outcomes recoverable.

## Current architecture on main

This document uses the implementation at the code baseline above. Proposed behavior below is work to implement, not an existing guarantee.

| Area | Current behavior | Code |
|---|---|---|
| Receiving files | Custom TUS endpoints write chunks to GCS and track offsets in PostgreSQL | [`TusUploadService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/tus/TusUploadService.kt) |
| Processing | The final chunk claims the upload and launches processing in an application-scoped coroutine. There is no database-polling processing worker | [`TusUploadService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/tus/TusUploadService.kt), [`Application.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/Application.kt) |
| File pipeline | Assemble, validate, scan, convert where needed, encrypt, upload to Fiks, then persist the receipt and clear staged objects | [`UploadProcessingService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/UploadProcessingService.kt) |
| Recovery | A sweep marks stalled pending and processing uploads as failed after three minutes and cleans their GCS objects | [`UploadRecoveryService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/UploadRecoveryService.kt) |
| Browser status | Submission state comes from the database, delivered over SSE using PostgreSQL notifications | [`SubmissionService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/status/SubmissionService.kt), [`Routes.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/status/Routes.kt) |
| Document reads | The service checks ownership locally and fetches the completed file from Fiks | [`DocumentRoutes.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/documents/DocumentRoutes.kt) |
| Final submission | The service builds an ettersendelse cover PDF, replaces it in Fiks, submits attachment metadata, and deletes local submission state on success or an already-exists response | [`EttersendelseService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/action/EttersendelseService.kt) |
| Deletion and retention | Individual upload deletion calls Fiks before deleting its local row. Whole-submission deletion removes local state before remote cleanup. Opted-in idle submissions expire after one hour | [`SubmissionDeletionService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/SubmissionDeletionService.kt), [`StaleSubmissionCleanupService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/StaleSubmissionCleanupService.kt) |

The schema at this baseline ends at `V1.16__add_conversion_metadata.sql`. Main at this baseline has no submit-attempt lease, submitted-state retention, or upload/delete/submit coordination across submissions sharing `navEksternRefId`. Submission validation checks limits on completed uploads but does not reject the submission merely because other uploads are unfinished.

### Existing consumer contracts

Preserve these behaviors during migration until their callers have moved:

- TUS creation, chunk upload, resume, and deletion, plus SSE status and completed-file download.
- `POST /submission/{submissionId}/submit`, including søknad and klage routing.
- `DELETE /submission/{submissionId}`, which deletes that submission and its remote files.
- TokenX `GET /vedlegg/{navEksternRefId}`, which builds attachment metadata from locally completed uploads.
- TokenX deletion by reference or category, including `keepMellomlagring` for reference-level deletion.
- Per-category submissions sharing `navEksternRefId`. Deleting one category must not delete its siblings' files.
- The `automatic_cleanup` opt-in policy for idle submissions. Søknad drafts are not subject to that idle cleanup.

Route paths above omit the `/sosialhjelp/upload` base path. The current `correlationId` links browser and server entries; it is not an enforced idempotency key.

## Confirmed requirements

- Multiple applications share the upload service.
- Fiks is the source of truth for stored files.
- Pending documents need to appear only in the current browser.
- Users can retry failed uploads. The service does not need to guarantee completion after browser closure, pod restart, or a Fiks outage.
- Extra files stored under the same `navEksternRefId` can cause submission rejection.
- The current maximum file size is 10 MiB.
- Scanning, validation, conversion, and encryption remain required.
- Preserve `navEksternRefId` and its existing assignment. The pilot does not require separate references.
- Develop the new upload flow separately and enable it by environment. Production stays on the existing flow until dev verification and cutover preparation are complete.

## Proposed architecture

Keep the shared upload service, using one synchronous request per file. Show pending documents using browser-local state.

Retain a small persistent transfer ledger for ownership, submission metadata, and unresolved operations. The new flow eventually replaces the application-scoped processing coroutines and staged chunk pipeline, but a failed request can require user intervention.

Build a separate multipart endpoint, transfer service, receipt resolver, and ledger queries. Reuse existing validation, scanning, conversion, and encryption through their interfaces. Keep the initial work out of the existing TUS processing pipeline. The exact ledger table layout remains an implementation decision; choose it so existing recovery and cleanup cannot discard new-flow intent.

Enforce an environment feature toggle in the backend. Enable the new path in dev and route a selected consumer through it. Keep the endpoint disabled in production until testing and cutover preparation are complete. Isolation comes from rollout control and separate implementation, without changing `navEksternRefId`.

Separate development limits changes to the production pipeline. It introduces temporary parallel code and ledger migration work. Coordination with legacy operations is a cutover requirement wherever both paths can affect the same `navEksternRefId`.

```text
Browser
  ├── Show selected document as pending
  └── Upload file with stable operation ID
           │
           ▼
      Upload service
        validate / scan / convert
        persist transfer intent
        encrypt / upload to Fiks
        persist confirmed receipt
           │
           ▼
      Return document receipt
      Browser marks document ready

Subsequent document reads → authorized upload API → Fiks
```

### Responsibility boundaries

| Component | Responsibility |
|---|---|
| Browser | Pending entries, local preview where supported, progress, retry |
| Upload service | Ownership checks, file processing, Fiks transfer, transfer ledger, remote-file verification |
| Consuming application backend | Draft contents, categories, submission eligibility, final submission orchestration |
| Fiks | Stored file existence and contents |

Moving submission orchestration is a separate migration after the transfer API is stable. Keep `EttersendelseService` and its søknad/klage contract working during the upload migration.

### Transfer ledger and remote reads

Store facts that Fiks cannot supply: authenticated owner and calling application, `navEksternRefId`, stable operation ID, category, intended remote filename, processed content type and size, SHA-512, conversion metadata, confirmed Fiks file ID, and unresolved mutation intent. Bind operation identity to an immutable payload and persist intent before issuing the remote upload.

Keep processed plaintext size and encrypted wire size distinct. The current Fiks request supplies encrypted size; the current local attachment metadata uses processed plaintext size and SHA-512. Store wire size separately if receipt resolution compares it with Fiks listing metadata.

Query Fiks for document existence and join its listing with local metadata by confirmed file ID. A local receipt means Fiks previously acknowledged the file; it does not prove that the file still exists.

Use the full operation UUID and processed-file extension in a deterministic remote filename. A stable filename helps discover an upload whose response was lost. Documented duplicate-filename rejection supports this approach, but does not establish full remote idempotency.

Resolve transfer outcomes as follows:

| Observation | Required behavior |
|---|---|
| Successful upload with matching receipt | Persist the confirmed file ID and completion atomically before reporting success |
| Duplicate-filename `409` | List files under `navEksternRefId` and resolve the exact operation filename; conflict alone is not success |
| Timeout, lost response, or unusable receipt | Retain an unknown outcome and reconcile from persisted intent |
| Failed listing or no matching file | Keep the operation unresolved; an empty listing does not rule out late completion |
| Multiple or incompatible matches | Retain an unresolved conflict and block submission |

Select upload response metadata by the intended filename, rather than assuming the first entry identifies the uploaded file. Listing has no checksum, so receipt matching depends on an exclusive operation filename and immutable intent. Confirm receipts through guarded database transitions, including when concurrent callers resolve the same operation. Verify ambiguous retry behavior in dev before enabling automatic resends.

### Fiks contract evidence

The [Mellomlagring OpenAPI specification](https://developers.fiks.ks.no/api/digisos-mellomlagring-api-v1.json) documents upload `202` with metadata and `409` when a document with the same filename already exists under `navEksternRefId`. A supplied log response confirms an observed `409` with the message `Mellomlagring inneholder allerede et dokument med samme filnavn`.

The specification exposes filename, file ID, size, and MIME type in listings, but no checksum or explicit idempotency key. It does not establish atomic duplicate rejection across concurrent uploads, listing visibility, or late-completion behavior. It also lacks an upload request-body definition and does not document `404` for file deletion, although the current client handles it.

The [Digisos documentation](https://developers.fiks.ks.no/tjenester/digisos/) says submission consumes and deletes the staged attachments. Exact-file-set and submission-retry behavior still need verification.

Use public documentation, client source, and dev tests to investigate remaining behavior. Contacting Fiks is not a prerequisite. Record documented guarantees separately from test observations. Where evidence ends, retain unresolved intent rather than infer success or definitive failure.

## Correctness rules

- An optimistic document is visibly pending, never presented as successfully stored.
- Report upload success only after persisting the confirmed Fiks receipt.
- A timeout or lost response means **unknown outcome**, not proven failure.
- Retries reuse a stable operation ID and resolve previous attempts before creating another remote file.
- Persist deletion intent before remote deletion. Retain it until deletion is confirmed. Treat an authoritative remote 404 as deleted; verify actual missing-file deletion responses in dev.
- A failed Fiks listing must not become an empty document list.
- Coordinate mutations and submission by `navEksternRefId`, including across categories.
- Block submission while mutations remain unresolved.
- Verify that the remote file set exactly matches the final manifest, including generated documents.

🔴 Rød sone: ownership, validation, encryption, and submission coordination require careful review.

## Current failure windows

- [`UploadProcessingService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/UploadProcessingService.kt) uploads to Fiks before committing the returned file ID. A crash between those steps leaves an untracked remote file.
- [`SubmissionDeletionService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/SubmissionDeletionService.kt) deletes local records before attempting remote deletion. A failed remote deletion loses its durable retry information.
- [`TusUploadService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/tus/TusUploadService.kt) reads the upload, deletes its known remote file, and then deletes local state in separate operations. It does not coordinate with processing or final submission, so a late upload can create a file after deletion has collected the known IDs.
- [`UploadRecoveryService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/upload/UploadRecoveryService.kt) marks interrupted processing as failed and deletes staged bytes.
- [`EttersendelseService.kt`](src/main/kotlin/no/nav/sosialhjelp/upload/action/EttersendelseService.kt) reads uploads and submits without excluding concurrent mutations. It then deletes local submission state, so local ownership and reference history do not survive successful submission.

Changing write order alone cannot close these windows. Uncertain remote outcomes need persistent intent and an explicit resolution path.

## Open decisions

| Question | Current position | Resolution |
|---|---|---|
| Does Fiks support upload idempotency? | No explicit idempotency key is documented; duplicate rejection is not full idempotency | Pending |
| Are remote filenames unique or enforced? | Duplicate filename returns `409`, documented and observed | Concurrent rejection remains to test |
| How quickly do uploads appear in listings? | Test in dev; retain unknown outcome when no match is visible | Pending |
| Can a timed-out upload complete after a subsequent listing? | Must account for late completion | Pending |
| How are unknown or duplicate remote files resolved? | Must not silently discard or submit them | Pending |
| Are multipart uploads practical on slow connections? | Preferred, subject to latency and timeout measurements | Pending |
| How does submission coordination by `navEksternRefId` work across apps? | Needs an explicit API contract before production cutover | Pending |
| What retention applies to unresolved operations and abandoned uploads? | Must define for the new ledger and verify legacy cleanup interactions | Pending |
| How are successful submission receipts retained and retries recognized? | Main deletes local state immediately; the new contract needs an explicit policy | Pending |
| Can the existing `correlationId` become the operation ID? | Decide uniqueness, ownership scope, and payload-conflict behavior before reusing it | Pending |
| What ledger table layout isolates the new flow? | Separate ledger queries; additive schema and legacy migration mapping | Pending |

## Work plan

### 1. Record contract evidence and investigate in dev

- [x] Record documented and observed duplicate-filename `409` behavior.
- [ ] Test sequential and concurrent same-filename uploads in isolated dev test data, without changing reference assignment.
- [ ] Upload, list, and download to verify receipt shape, file identity, and readability.
- [ ] Test lost upload responses, listing visibility, and late completion.
- [ ] Test repeated deletion and record missing-file responses.
- [ ] Confirm submission's exact-file-set requirements.
- [ ] Confirm remote retention and deletion behavior.
- [ ] Agree on handling unknown outcomes and unexpected remote files.

**Exit criterion:** Documented behavior and observations are recorded separately, and unresolved mutations have a conservative recovery path. Investigation does not depend on contacting Fiks and can continue alongside dev implementation.

### 2. Define the transfer API and ledger

- [ ] Define stable operation IDs and request-retry behavior.
- [ ] Define upload receipts and operation-status lookup.
- [ ] Specify the minimum ledger fields, transfer states, retention, and table layout independently of the TUS pipeline.
- [ ] Define durable deletion intent.
- [ ] Define coordination by `navEksternRefId` and manifest verification for the new flow.
- [ ] Define successful-submission receipt retention and retry behavior before replacing immediate local cleanup.
- [ ] Plan additive schema changes and map legacy fields for later migration, starting from the actual schema at implementation time.
- [ ] Review ownership checks for user and application access.

**Exit criterion:** API behavior is agreed for success, failure, lost responses, retries, and concurrent operations.

### 3. Pilot single-request upload

- [ ] Add a separate multipart endpoint, transfer service, receipt resolver, and ledger queries.
- [ ] Add a backend-enforced environment feature toggle: enabled for dev testing, disabled in production.
- [ ] Preserve `navEksternRefId` and its existing assignment.
- [ ] Add browser-local pending entries in one consumer.
- [ ] Preserve existing file-processing safeguards.
- [ ] Exercise reads, category metadata, deletion, retention, and submission with the new ledger in dev.
- [ ] Verify that existing recovery and cleanup cannot erase new-flow intent.
- [ ] Measure upload and processing latency, including slow mobile connections.
- [ ] Verify ingress and request timeouts and bound concurrent processing.

**Exit criterion:** The dev pilot meets agreed latency and failure-handling targets with production still using the existing path. Retain resumable transport if measurements show it is needed.

### 4. Verify failure behavior

- [ ] Lose the Fiks upload response after remote success.
- [ ] Restart the service after remote success but before local confirmation.
- [ ] Fail remote deletion.
- [ ] Retry the same operation concurrently.
- [ ] Race upload, delete, and submission across categories.
- [ ] Delete a whole submission while its existing TUS processing coroutine is uploading to Fiks.
- [ ] Retry final submission after Fiks accepts it but local cleanup or the client response fails.
- [ ] Test unavailable or delayed Fiks listings, including late upload completion.

**Exit criterion:** No false success, unsafe duplicate retry, or submission with an unresolved file set.

### 5. Prepare production cutover and migrate the ledger

- [ ] Map existing completed uploads into the ledger as needed, preserving actual remote filenames, metadata, and Fiks file IDs.
- [ ] Reconcile local records with Fiks and record unresolved discrepancies.
- [ ] Resolve in-flight TUS uploads or let them finish on the old path with their outcomes retained.
- [ ] Include legacy upload, deletion, retention, and submission paths in coordination wherever they coexist under the same `navEksternRefId`.
- [ ] Verify reads, category metadata, deletion, cleanup, and submission across migrated and new records.
- [ ] Test rollback with records created through the new path.
- [ ] Define how ledger migrations and corrections continue during rollout without losing ownership or confirmed receipts.

**Exit criterion:** Dev verification is complete and migration, coexistence, cleanup, and rollback behavior are ready before enabling the production toggle.

### 6. Switch consumers and remove obsolete machinery

- [ ] Switch consumers gradually.
- [ ] Drain existing TUS uploads.
- [ ] Remove TUS and GCS chunk staging when unused.
- [ ] Remove the application-scoped processing launch and stuck-upload recovery when the TUS path is drained.
- [ ] Remove upload-status SSE when no consumer needs it.
- [ ] Review PostgreSQL notification infrastructure after confirming no remaining status consumers need it.
- [ ] Move domain submission orchestration to the owning backends in a separate consumer migration, covering both søknad and klage.

**Exit criterion:** All consumers use the agreed contract and obsolete infrastructure has no remaining workload.

## Rollout and rollback

Use a backend-enforced environment feature toggle. Test the new endpoint and selected consumer in dev while production uses the existing path. Preserve `navEksternRefId`; no pilot-specific reference assignment is required. Keep operation identities distinguishable between paths.

Enable production only after dev verification and cutover preparation. Route new uploads to the new endpoint while existing TUS uploads finish on the old path or have their outcomes resolved. Both paths must participate in coordination wherever they affect the same `navEksternRefId` during cutover.

Rollback routes new uploads to the existing endpoint. Preserve and support ledger records already created through the new path, including unresolved operations. Routing rollback alone does not undo remote uploads or ledger migrations.

Keep schema changes additive while the existing endpoint remains a rollback option. Drain in-flight processing coroutines and resolve their remote outcomes before removing GCS staging or changing cleanup. A cleanup timer must not discard unresolved intent or delete files needed for submission under the same `navEksternRefId`.

## Operational checks

Track:

- End-to-end upload latency and success rate.
- Number and age of unresolved operations.
- Pending deletion age.
- Remote manifest mismatches.

Set numerical targets and alert thresholds during the pilot.

## Decision log

| Date | Decision | Reason |
|---|---|---|
| 2026-11-04 | Propose browser-local optimistic display | Pending documents do not need cross-device or refresh persistence |
| 2026-11-04 | Propose synchronous per-file upload | Users can retry failures |
| 2026-11-04 | Retain a narrow transfer ledger | Remote outcomes can be uncertain, and extra files block submission |
| 2026-11-04 | Rebase the plan on main at `f34715d` | Main uses direct background coroutine processing and immediate submission cleanup; the earlier draft included assumptions from another branch |
| 2026-11-06 | Build the new flow separately, with an environment feature toggle | Test in dev before enabling production without first refactoring the TUS pipeline |
| 2026-11-06 | Preserve `navEksternRefId` and its assignment | The existing reference is fixed by the consuming flow; rollout does not require separate references |
| 2026-11-06 | Record duplicate-filename rejection as documented and observed | Swagger specifies `409` and a supplied log response confirms an example; concurrency and visibility remain unestablished |
| 2026-11-06 | Investigate remaining Fiks behavior through documentation and dev tests | Contacting Fiks is not a prerequisite; uncertain outcomes remain explicit |
| 2026-11-06 | Prepare legacy-ledger migration and coexistence for production cutover | Separate development postpones production integration work until the new flow has been tested |

## Review notes

Record discoveries, unresolved disagreements, and changes to the requirements here as work progresses.
