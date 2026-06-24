package no.nav.sosialhjelp.upload.tus

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.Upload
import no.nav.sosialhjelp.upload.upload.UploadNotifications
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB queries for the TUS protocol layer: creating uploads, tracking chunk offsets,
 * ownership checks, and deletion.
 */
class TusUploadQueries {
    class OffsetMismatchException(message: String) : RuntimeException(message)

    fun create(
        tx: Configuration,
        submissionId: UUID,
        filename: String,
        filesize: Long,
    ): UUID? {
        val uploadId = UUID.randomUUID()
        val gcsKey = "uploads/$uploadId"
        return tx
            .dsl()
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .set(UPLOAD.SIZE, filesize)
            .set(UPLOAD.UPLOAD_OFFSET, 0L)
            .set(UPLOAD.GCS_KEY, gcsKey)
            .returning(UPLOAD.ID)
            .fetchOne()
            ?.get(UPLOAD.ID)
            ?.also { UploadNotifications.notifyChange(tx, it) }
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

    fun getUploadInfo(
        tx: Configuration,
        uploadId: UUID,
    ): Pair<Long, Long> =
        tx
            .dsl()
            .select(UPLOAD.UPLOAD_OFFSET, UPLOAD.SIZE)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .fetchSingle()
            .let { it.get(UPLOAD.UPLOAD_OFFSET)!! to it.get(UPLOAD.SIZE)!! }

    /**
     * Updates the upload offset after a chunk is received.
     * Throws [OffsetMismatchException] if the current offset doesn't match [expectedOffset].
     * The actual chunk bytes are written to GCS by the caller; only metadata is tracked here.
     */
    fun appendChunk(
        tx: Configuration,
        uploadId: UUID,
        expectedOffset: Long,
        chunkSize: Int,
    ): Pair<Long, Long> {
        // Lock the row to serialize concurrent chunk uploads to the same upload ID.
        tx.dsl().select(UPLOAD.ID).from(UPLOAD).where(UPLOAD.ID.eq(uploadId)).forUpdate().fetchOne()
            ?: throw OffsetMismatchException("Upload $uploadId not found")
        val newOffset = expectedOffset + chunkSize
        val affected =
            tx.dsl().update(UPLOAD)
                .set(UPLOAD.UPLOAD_OFFSET, newOffset)
                .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
                .where(UPLOAD.ID.eq(uploadId))
                .and(UPLOAD.UPLOAD_OFFSET.eq(expectedOffset))
                .execute()
        if (affected == 0) {
            throw OffsetMismatchException("Upload $uploadId offset mismatch: expected $expectedOffset")
        }
        val totalSize =
            tx
                .dsl()
                .select(UPLOAD.SIZE)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
                .get(UPLOAD.SIZE)!!
        return totalSize to newOffset
    }

    /**
     * Atomically claims an upload for processing by transitioning PENDING → PROCESSING.
     * Returns true if this caller claimed it, false if already claimed or processed.
     */
    fun claimForProcessing(
        tx: Configuration,
        uploadId: UUID,
    ): Boolean =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.PROCESSING.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .and(UPLOAD.PROCESSING_STATUS.eq(Status.PENDING.name))
            .execute() > 0

    fun getUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Upload =
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
            )
            .from(UPLOAD)
            .join(SUBMISSION).on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(UPLOAD.ID.eq(uploadId))
            .fetchSingle()
            .let {
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
                        it.get(UPLOAD.PROCESSING_STATUS)
                            ?.let { s -> Status.valueOf(s) }
                            ?: error("No processing status. Was it not selected?"),
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
