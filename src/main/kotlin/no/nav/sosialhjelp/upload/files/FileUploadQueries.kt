package no.nav.sosialhjelp.upload.files

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.upload.PipelineResult
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.Upload
import no.nav.sosialhjelp.upload.upload.UploadNotifications
import org.jooq.Configuration
import org.jooq.exception.NoDataFoundException
import java.util.UUID

class FileUploadQueries {
    @Suppress("LongParameterList")
    fun insertCompleted(
        tx: Configuration,
        uploadId: UUID,
        submissionId: UUID,
        filename: String,
        originalSize: Long,
        correlationId: UUID,
        stored: PipelineResult.Stored,
    ) {
        tx
            .dsl()
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .set(UPLOAD.SIZE, originalSize)
            .set(UPLOAD.UPLOAD_OFFSET, originalSize)
            .set(UPLOAD.GCS_KEY, null as String?)
            .set(UPLOAD.CORRELATION_ID, correlationId)
            .set(UPLOAD.FIL_ID, stored.filId)
            .set(UPLOAD.MELLOMLAGRING_FILNAVN, stored.mellomlagringFilnavn)
            .set(UPLOAD.MELLOMLAGRING_STORRELSE, stored.storedSize)
            .set(UPLOAD.SHA512, stored.sha512)
            .set(UPLOAD.CONVERTED, stored.converted)
            .set(UPLOAD.CONTENT_TYPE, stored.contentType)
            .set(UPLOAD.PROCESSING_STATUS, Status.COMPLETE.name)
            .execute()
        UploadNotifications.notifyChange(tx, uploadId)
    }

    fun isOwnedByUser(
        tx: Configuration,
        uploadId: UUID,
        ownerIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(UPLOAD)
            .join(SUBMISSION)
            .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(UPLOAD.ID.eq(uploadId))
            .and(SUBMISSION.OWNER_IDENT.eq(ownerIdent))
            .fetchSingle()
            .value1() > 0

    fun getSubmissionIdFromUploadId(
        tx: Configuration,
        uploadId: UUID,
    ): UUID? =
        tx
            .dsl()
            .select(UPLOAD.SUBMISSION_ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .fetchOne()
            ?.get(UPLOAD.SUBMISSION_ID)

    fun getUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Upload = findUpload(tx, uploadId) ?: throw NoDataFoundException("No upload with id $uploadId")

    fun findUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Upload? =
        tx
            .dsl()
            .select(
                UPLOAD.ID,
                UPLOAD.ORIGINAL_FILENAME,
                UPLOAD.FIL_ID,
                SUBMISSION.NAV_EKSTERN_REF_ID,
                UPLOAD.MELLOMLAGRING_FILNAVN,
                UPLOAD.SIZE,
                UPLOAD.MELLOMLAGRING_STORRELSE,
                UPLOAD.PROCESSING_STATUS,
                UPLOAD.CONTENT_TYPE,
            ).from(UPLOAD)
            .join(SUBMISSION)
            .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(UPLOAD.ID.eq(uploadId))
            .fetchOne()
            ?.let {
                Upload(
                    id = it.get(UPLOAD.ID),
                    originalFilename = it.get(UPLOAD.ORIGINAL_FILENAME),
                    errors = emptyList(),
                    filId = it.get(UPLOAD.FIL_ID),
                    navEksternRefId = it.get(SUBMISSION.NAV_EKSTERN_REF_ID),
                    mellomlagringFilnavn = it.get(UPLOAD.MELLOMLAGRING_FILNAVN),
                    fileSize = it.get(UPLOAD.SIZE),
                    mellomlagringStorrelse = it.get(UPLOAD.MELLOMLAGRING_STORRELSE),
                    status =
                        it
                            .get(UPLOAD.PROCESSING_STATUS)
                            ?.let { s -> Status.valueOf(s) }
                            ?: error("No processing status. Was it not selected?"),
                    contentType = it.get(UPLOAD.CONTENT_TYPE),
                )
            }

    fun deleteUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Int {
        val submissionId =
            getSubmissionIdFromUploadId(tx, uploadId)
                ?: error("No submissionId for upload")
        val deleted =
            tx
                .dsl()
                .delete(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .execute()
        tx.dsl().execute("SELECT pg_notify('submission_update', ?)", submissionId.toString())
        return deleted
    }
}
