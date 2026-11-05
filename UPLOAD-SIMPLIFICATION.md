# Upload architecture simplification

**Status:** Proposed direction, pending contract verification

**Scope:** Simplify shared document upload to Fiks Mellomlager

**Code baseline:** `main` at `f34715d` (submission deletion endpoint, PR #55)

**Last updated:** 2026-11-04

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

The schema currently ends at `V1.16__add_conversion_metadata.sql`. Main has no submit-attempt lease, submitted-state retention, or group-wide upload/delete/submit coordination. Submission validation checks limits on completed uploads but does not reject the submission merely because other uploads are unfinished.

### Existing consumer contracts

Preserve these behaviors during migration until their callers have moved:

- TUS creation, chunk upload, resume, and deletion, plus SSE status and completed-file download.
- `POST /submission/{submissionId}/submit`, including søknad and klage routing.
- `DELETE /submission/{submissionId}`, which deletes that submission and its remote files.
- TokenX `GET /vedlegg/{navEksternRefId}`, which builds attachment metadata from locally completed uploads.
- TokenX deletion by reference or category, including `keepMellomlagring` for reference-level deletion.
- Per-category submissions sharing a Fiks group. Deleting one category must not delete its siblings' files.
- The `automatic_cleanup` opt-in policy for idle submissions. Søknad drafts are not subject to that idle cleanup.

Route paths above omit the `/sosialhjelp/upload` base path. The current `correlationId` links browser and server entries; it is not an enforced idempotency key.

## Confirmed requirements

- Multiple applications share the upload service.
- Fiks is the source of truth for stored files.
- Pending documents need to appear only in the current browser.
- Users can retry failed uploads. The service does not need to guarantee completion after browser closure, pod restart, or a Fiks outage.
- Extra files in a Fiks group can cause submission rejection.
- The current maximum file size is 10 MiB.
- Scanning, validation, conversion, and encryption remain required.

## Proposed architecture

Keep the shared upload service, using one synchronous request per file. Show pending documents using browser-local state.

Retain a small persistent transfer ledger for ownership, submission metadata, and unresolved operations. This replaces the application-scoped processing coroutines and staged chunk pipeline, but a failed request can require user intervention. Start by evolving the existing tables rather than introducing a second local inventory.

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

Store facts that Fiks cannot supply: authenticated owner and calling application, group reference, stable operation ID, category, final filename, content type, SHA-512, confirmed Fiks file ID, and unresolved mutation intent.

Query Fiks for document existence and join its listing with local metadata by confirmed file ID. A local receipt means Fiks previously acknowledged the file; it does not prove that the file still exists.

Use the full operation UUID in a deterministic remote filename where the Fiks contract allows it. A stable filename helps discover an upload whose response was lost. It does not provide remote idempotency.

## Correctness rules

- An optimistic document is visibly pending, never presented as successfully stored.
- Report upload success only after persisting the confirmed Fiks receipt.
- A timeout or lost response means **unknown outcome**, not proven failure.
- Retries reuse a stable operation ID and resolve previous attempts before creating another remote file.
- Persist deletion intent before remote deletion. Retain it until deletion is confirmed. Remote 404 counts as deleted.
- A failed Fiks listing must not become an empty document list.
- Coordinate mutations and submission at the Fiks group (`navEksternRefId`) boundary, including across categories.
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
| Does Fiks support upload idempotency? | Not established by the current client | Pending |
| Are remote filenames unique or enforced? | A deterministic filename helps discovery but does not guarantee idempotency | Pending |
| How quickly do uploads appear in listings? | Must verify before designing timeout recovery | Pending |
| Can a timed-out upload complete after a subsequent listing? | Must account for late completion | Pending |
| How are unknown or duplicate remote files resolved? | Must not silently discard or submit them | Pending |
| Are multipart uploads practical on slow connections? | Preferred, subject to latency and timeout measurements | Pending |
| How does group-wide submission coordination work across apps? | Needs an explicit API contract | Pending |
| What retention applies to unresolved operations and abandoned groups? | Must define before changing cleanup | Pending |
| How are successful submission receipts retained and retries recognized? | Main deletes local state immediately; the new contract needs an explicit policy | Pending |
| Can the existing `correlationId` become the operation ID? | Decide uniqueness, ownership scope, and payload-conflict behavior before reusing it | Pending |

## Work plan

### 1. Verify the Fiks contract

- [ ] Establish idempotency and duplicate-filename behavior.
- [ ] Establish listing consistency and late-completion behavior.
- [ ] Confirm submission's exact-file-set requirements.
- [ ] Confirm remote retention and deletion behavior.
- [ ] Agree on handling unknown outcomes and unexpected remote files.

**Exit criterion:** We can describe a safe recovery path for every uncertain remote mutation.

### 2. Define the transfer API and ledger

- [ ] Define stable operation IDs and request-retry behavior.
- [ ] Define upload receipts and operation-status lookup.
- [ ] Specify the minimum ledger fields and retention.
- [ ] Define durable deletion intent.
- [ ] Define group coordination and manifest verification.
- [ ] Include the existing TUS, deletion, retention, and submission paths in coordination while they coexist.
- [ ] Define successful-submission receipt retention and retry behavior before replacing immediate local cleanup.
- [ ] Map existing submission/upload fields to the ledger using additive migrations from main's V1.16 schema.
- [ ] Review ownership checks for user and application access.

**Exit criterion:** API behavior is agreed for success, failure, lost responses, retries, and concurrent operations.

### 3. Pilot single-request upload

- [ ] Add multipart upload alongside TUS.
- [ ] Add browser-local pending entries in one consumer.
- [ ] Preserve existing file-processing safeguards.
- [ ] Keep existing submission, category metadata, and cleanup contracts working for both upload paths.
- [ ] Measure upload and processing latency, including slow mobile connections.
- [ ] Verify ingress and request timeouts and bound concurrent processing.

**Exit criterion:** The pilot meets agreed latency and failure-handling targets. Retain resumable transport if measurements show it is needed.

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

### 5. Migrate and remove obsolete machinery

- [ ] Switch consumers gradually.
- [ ] Drain existing TUS uploads.
- [ ] Remove TUS and GCS chunk staging when unused.
- [ ] Remove the application-scoped processing launch and stuck-upload recovery when the TUS path is drained.
- [ ] Remove upload-status SSE when no consumer needs it.
- [ ] Review PostgreSQL notification infrastructure after confirming no remaining status consumers need it.
- [ ] Move domain submission orchestration to the owning backends in a separate consumer migration, covering both søknad and klage.

**Exit criterion:** All consumers use the agreed contract and obsolete infrastructure has no remaining workload.

## Rollout and rollback

Keep the existing upload path available during the pilot. Route new uploads through a controlled consumer rollout, with operation identities distinguishable between paths.

Rollback routes new uploads to the existing endpoint. Preserve ledger records and reconcile operations already started through the new path. Both paths must participate in group-wide coordination while they coexist.

Keep schema changes additive while the existing endpoint remains a rollback option. Drain in-flight processing coroutines and resolve their remote outcomes before removing GCS staging or changing cleanup. A cleanup timer must not discard unresolved intent or delete files needed by a submitting group.

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

## Review notes

Record discoveries, unresolved disagreements, and changes to the requirements here as work progresses.
