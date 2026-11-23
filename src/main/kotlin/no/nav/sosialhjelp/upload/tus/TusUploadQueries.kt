package no.nav.sosialhjelp.upload.tus

import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.UploadNotifications
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB queries for the TUS protocol layer: creating uploads, tracking chunk offsets,
 * ownership checks, and deletion.
 */
class TusUploadQueries {
    class OffsetMismatchException(
        message: String,
    ) : RuntimeException(message)

    /**
     * Returns the new upload id, or null if (submissionId, correlationId) already exists
     * (a retried creation request). Use [findIdByCorrelationId] to get the existing one.
     */
    fun create(
        tx: Configuration,
        submissionId: UUID,
        filename: String,
        filesize: Long,
        correlationId: UUID?,
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
            .set(UPLOAD.CORRELATION_ID, correlationId)
            .onConflict(UPLOAD.SUBMISSION_ID, UPLOAD.CORRELATION_ID)
            .where(UPLOAD.CORRELATION_ID.isNotNull)
            .doNothing()
            .returning(UPLOAD.ID)
            .fetchOne()
            ?.get(UPLOAD.ID)
            ?.also { UploadNotifications.notifyChange(tx, it) }
    }

    fun findIdByCorrelationId(
        tx: Configuration,
        submissionId: UUID,
        correlationId: UUID,
    ): UUID? =
        tx
            .dsl()
            .select(UPLOAD.ID)
            .from(UPLOAD)
            .where(UPLOAD.SUBMISSION_ID.eq(submissionId))
            .and(UPLOAD.CORRELATION_ID.eq(correlationId))
            .fetchOne()
            ?.get(UPLOAD.ID)

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
        tx
            .dsl()
            .select(UPLOAD.ID)
            .from(UPLOAD)
            .where(UPLOAD.ID.eq(uploadId))
            .forUpdate()
            .fetchOne()
            ?: throw OffsetMismatchException("Upload $uploadId not found")
        val newOffset = expectedOffset + chunkSize
        val affected =
            tx
                .dsl()
                .update(UPLOAD)
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
}
