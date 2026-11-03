@file:Suppress("LongParameterList")

package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.submitLockActive
import no.nav.sosialhjelp.upload.validation.Validation
import org.jooq.Condition
import org.jooq.Configuration
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

/** A crashed or hung attempt is retried this many times in total before the upload is failed. */
const val MAX_PROCESSING_ATTEMPTS = 3

/**
 * DB queries for the post-upload processing pipeline:
 * reading upload data for processing, persisting the result, recording errors,
 * and marking uploads as failed.
 */
class UploadProcessingQueries {
    fun getUploadForProcessing(
        tx: Configuration,
        uploadId: UUID,
    ): UploadForProcessing {
        val record =
            tx
                .dsl()
                .select(UPLOAD.ORIGINAL_FILENAME, UPLOAD.GCS_KEY, UPLOAD.SUBMISSION_ID)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
        val submissionId = record.get(UPLOAD.SUBMISSION_ID)!!
        val submissionRecord =
            tx
                .dsl()
                .select(SUBMISSION.NAV_EKSTERN_REF_ID, SUBMISSION.FIKS_DIGISOS_ID)
                .from(SUBMISSION)
                .where(SUBMISSION.ID.eq(submissionId))
                .fetchSingle()
        return UploadForProcessing(
            filename = record.get(UPLOAD.ORIGINAL_FILENAME)!!,
            gcsKey = record.get(UPLOAD.GCS_KEY)!!,
            submissionId = submissionId,
            navEksternRefId = submissionRecord.get(SUBMISSION.NAV_EKSTERN_REF_ID)!!,
            fiksDigisosId = submissionRecord.get(SUBMISSION.FIKS_DIGISOS_ID),
        )
    }

    /** Identifies one processing attempt. Every write the attempt makes is fenced on [attemptId]. */
    data class Claim(
        val uploadId: UUID,
        val attemptId: UUID,
        val attempt: Int,
    )

    private fun claimable(leaseCutoff: OffsetDateTime): Condition =
        UPLOAD.PROCESSING_STATUS
            .eq(Status.READY.name)
            .or(
                UPLOAD.PROCESSING_STATUS
                    .eq(Status.PROCESSING.name)
                    // Legacy processors do not fence writes. Never reclaim them until all old pods stopped.
                    .and(UPLOAD.PROCESSING_ATTEMPT_ID.isNotNull)
                    .and(UPLOAD.PROCESSING_STARTED_AT.lt(leaseCutoff))
                    .and(UPLOAD.PROCESSING_ATTEMPTS.lt(MAX_PROCESSING_ATTEMPTS)),
            )

    /**
     * Claims the next READY upload, or a PROCESSING upload whose lease expired (the pod died or
     * the attempt hung). Returns null if there is nothing claimable right now.
     *
     * The upload row and its submission row are locked together with SKIP LOCKED. Other writers
     * lock submission, then upload, and wait; this query never waits, so it cannot deadlock with
     * them. Rows another worker holds are skipped, so parallel workers get different uploads.
     * Uploads in a submission with a live submit lock are never candidates: a file processed
     * during a submit would reach mellomlagring without being in vedlegg.json. Holding the
     * submission row lock keeps that check valid until commit (claimSubmit updates the same row).
     */
    @Suppress("ReturnCount")
    fun claimNext(
        tx: Configuration,
        leaseCutoff: OffsetDateTime,
    ): Claim? {
        val candidate =
            tx
                .dsl()
                .select(UPLOAD.ID)
                .from(UPLOAD)
                .join(SUBMISSION)
                .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
                .where(claimable(leaseCutoff))
                .and(DSL.not(submitLockActive()))
                .orderBy(UPLOAD.UPDATED_AT)
                .limit(1)
                .forUpdate()
                .of(UPLOAD, SUBMISSION)
                .skipLocked()
                .fetchOne() ?: return null
        val uploadId = candidate.get(UPLOAD.ID)!!

        val attemptId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        // Plain SQL: jOOQ maps UPDATE ... RETURNING into the generated upload row type, which does
        // not contain the V1.18 columns until jOOQ is regenerated. Parameters are bound, not concatenated.
        val attempt =
            tx
                .dsl()
                .resultQuery(
                    """
                    UPDATE upload
                       SET processing_status = 'PROCESSING',
                           processing_attempt_id = {0},
                           processing_started_at = {1},
                           processing_attempts = processing_attempts + 1,
                           updated_at = {1}
                     WHERE id = {2}
                       AND {3}
                    RETURNING processing_attempts
                    """.trimIndent(),
                    DSL.value(attemptId),
                    DSL.value(now),
                    DSL.value(uploadId),
                    claimable(leaseCutoff),
                ).fetchOne()
                ?.get(0, Int::class.java) ?: return null
        UploadNotifications.notifyChange(tx, uploadId)
        return Claim(uploadId, attemptId, attempt)
    }

    /** True only while [attemptId] still owns the upload. Null means "not from a processing attempt". */
    private fun ownedBy(attemptId: UUID?): Condition =
        if (attemptId == null) {
            DSL.noCondition()
        } else {
            UPLOAD.PROCESSING_STATUS
                .eq(Status.PROCESSING.name)
                .and(UPLOAD.PROCESSING_ATTEMPT_ID.eq(attemptId))
        }

    /** Keep ownership stable while replacing the remote file and committing its ID. */
    fun lockOwnedAttempt(
        tx: Configuration,
        uploadId: UUID,
        attemptId: UUID,
    ): Boolean =
        tx
            .dsl()
            .select(UPLOAD.ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .and(ownedBy(attemptId))
            .forUpdate()
            .fetchOne() != null

    @Suppress("LongParameterList")
    fun setFilId(
        tx: Configuration,
        uploadId: UUID,
        attemptId: UUID,
        filId: UUID,
        mellomlagringFilnavn: String,
        mellomlagringStorrelse: Long,
        sha512: String,
        converted: Boolean,
        contentType: String,
    ): Boolean =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.FIL_ID, filId)
            .set(UPLOAD.MELLOMLAGRING_FILNAVN, mellomlagringFilnavn)
            .set(UPLOAD.MELLOMLAGRING_STORRELSE, mellomlagringStorrelse)
            .set(UPLOAD.SHA512, sha512)
            .set(UPLOAD.CONVERTED, converted)
            .set(UPLOAD.CONTENT_TYPE, contentType)
            .set(UPLOAD.PROCESSING_STATUS, Status.COMPLETE.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .setNull(UPLOAD.GCS_KEY)
            // UPLOAD.SIZE is intentionally not modified — it holds the original Upload-Length.
            .where(UPLOAD.ID.eq(uploadId))
            .and(ownedBy(attemptId))
            .execute() == 1

    /** Marks the upload FAILED. With [attemptId], only if that attempt still owns it. */
    fun markFailed(
        tx: Configuration,
        uploadId: UUID,
        attemptId: UUID? = null,
    ): Boolean =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .and(ownedBy(attemptId))
            .execute() == 1

    /** Records validation errors and marks the upload FAILED. A fenced-out attempt writes nothing. */
    fun addErrors(
        tx: Configuration,
        uploadId: UUID,
        validations: List<Validation>,
        attemptId: UUID? = null,
    ): Boolean {
        if (!markFailed(tx, uploadId, attemptId)) return false
        val errorTable = no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
        validations.forEach {
            tx
                .dsl()
                .insertInto(errorTable)
                .set(errorTable.UPLOAD, uploadId)
                .set(errorTable.CODE, it.code.name)
                .set(errorTable.ID, UUID.randomUUID())
                .execute()
        }
        UploadNotifications.notifyChange(tx, uploadId)
        return true
    }
}
