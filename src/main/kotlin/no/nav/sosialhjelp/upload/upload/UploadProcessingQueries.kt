@file:Suppress("LongParameterList")

package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.submitLockActive
import no.nav.sosialhjelp.upload.validation.Validation
import org.jooq.Configuration
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

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

    /**
     * Claims the next READY upload. Processing is attempted once; recovery fails abandoned work.
     *
     * The upload row and its submission row are locked together with SKIP LOCKED. Other writers
     * lock submission, then upload, and wait; this query never waits, so it cannot deadlock with
     * them. Rows another worker holds are skipped, so parallel workers get different uploads.
     * Uploads in a submission with a live submit lock are never candidates: a file processed
     * during a submit would reach mellomlagring without being in vedlegg.json. Holding the
     * submission row lock keeps that check valid until commit (claimSubmit updates the same row).
     */
    @Suppress("ReturnCount")
    fun claimNext(tx: Configuration): UUID? {
        val candidate =
            tx
                .dsl()
                .select(UPLOAD.ID)
                .from(UPLOAD)
                .join(SUBMISSION)
                .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
                .where(UPLOAD.PROCESSING_STATUS.eq(Status.READY.name))
                .and(DSL.not(submitLockActive()))
                .orderBy(UPLOAD.UPDATED_AT)
                .limit(1)
                .forUpdate()
                .of(UPLOAD, SUBMISSION)
                .skipLocked()
                .fetchOne() ?: return null
        val uploadId = candidate.get(UPLOAD.ID)!!

        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.PROCESSING.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
        UploadNotifications.notifyChange(tx, uploadId)
        return uploadId
    }

    @Suppress("LongParameterList")
    fun setFilId(
        tx: Configuration,
        uploadId: UUID,
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
            .and(UPLOAD.PROCESSING_STATUS.eq(Status.PROCESSING.name))
            .execute() == 1

    /** Marks a processing upload FAILED, without changing a completed or deleted upload. */
    fun markFailed(
        tx: Configuration,
        uploadId: UUID,
        onlyProcessing: Boolean = false,
    ): Boolean =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .and(if (onlyProcessing) UPLOAD.PROCESSING_STATUS.eq(Status.PROCESSING.name) else DSL.noCondition())
            .execute() == 1

    /** Records validation errors only if the upload can still be marked failed. */
    fun addErrors(
        tx: Configuration,
        uploadId: UUID,
        validations: List<Validation>,
        onlyProcessing: Boolean = false,
    ): Boolean {
        if (!markFailed(tx, uploadId, onlyProcessing)) return false
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
