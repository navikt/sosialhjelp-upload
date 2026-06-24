package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.validation.Validation
import org.jooq.Configuration
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
        val record = tx
            .dsl()
            .select(UPLOAD.ORIGINAL_FILENAME, UPLOAD.GCS_KEY, UPLOAD.SUBMISSION_ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .fetchSingle()
        val submissionId = record.get(UPLOAD.SUBMISSION_ID)!!
        val submissionRecord = tx
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

    fun setFilId(
        tx: Configuration,
        uploadId: UUID,
        filId: UUID,
        mellomlagringFilnavn: String,
        mellomlagringStorrelse: Long,
        sha512: String,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.FIL_ID, filId)
            .set(UPLOAD.MELLOMLAGRING_FILNAVN, mellomlagringFilnavn)
            .set(UPLOAD.MELLOMLAGRING_STORRELSE, mellomlagringStorrelse)
            .set(UPLOAD.SHA512, sha512)
            .set(UPLOAD.PROCESSING_STATUS, Status.COMPLETE.name)
            .setNull(UPLOAD.GCS_KEY)
            // UPLOAD.SIZE is intentionally not modified — it holds the original Upload-Length
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    fun markFailed(
        tx: Configuration,
        uploadId: UUID,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    fun addErrors(
        tx: Configuration,
        uploadId: UUID,
        validations: List<Validation>,
    ) {
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
        markFailed(tx, uploadId)
        UploadNotifications.notifyChange(tx, uploadId)
    }
}
