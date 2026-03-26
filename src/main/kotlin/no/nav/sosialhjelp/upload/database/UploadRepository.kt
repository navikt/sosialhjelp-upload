package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.validation.Validation
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2

data class UploadWithFilename(
    val id: UUID?,
    val originalFilename: String?,
    val errors: List<ValidationCode>,
    val filId: UUID?,
    val navEksternRefId: String?,
    val mellomlagringFilnavn: String?,
    val fileSize: Long?,
    val mellomlagringStorrelse: Long?,
)

data class UploadForProcessing(
    val filename: String,
    val chunkData: ByteArray,
    val submissionId: UUID,
    val navEksternRefId: String,
)

class UploadRepository(
    val notificationService: SubmissionNotificationService,
) {
    class OffsetMismatchException(message: String) : RuntimeException(message)

    fun create(
        tx: Configuration,
        submissionId: UUID,
        filename: String,
        filesize: Long,
    ): UUID? =
        tx
            .dsl()
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, UUID.randomUUID())
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .set(UPLOAD.SIZE, filesize)
            .set(UPLOAD.UPLOAD_OFFSET, 0L)
            .returning(UPLOAD.ID)
            .fetchOne()
            ?.get(UPLOAD.ID)
            ?.also { notifyChange(tx, it) }

    fun notifyChange(
        tx: Configuration,
        uploadId: UUID,
    ) {
        val submissionId = getSubmissionIdFromUploadId(tx, uploadId) ?: return
        // Send NOTIFY within the transaction so it fires atomically on commit
        tx.dsl().execute("SELECT pg_notify('submission_update', ?)", submissionId.toString())
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
     * Appends [data] to the upload only if the current offset matches [expectedOffset].
     * Throws [OffsetMismatchException] if the offset doesn't match — callers must return 409 Conflict.
     */
    fun appendChunk(
        tx: Configuration,
        uploadId: UUID,
        expectedOffset: Long,
        data: ByteArray,
    ): Pair<Long, Long> {
        val newOffset = expectedOffset + data.size
        val affected = tx.dsl().execute(
            "UPDATE upload SET chunk_data = COALESCE(chunk_data, ''::bytea) || ?, upload_offset = ?, updated_at = NOW() WHERE id = ? AND upload_offset = ?",
            data, newOffset, uploadId, expectedOffset,
        )
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
            .set(UPLOAD.PROCESSING_STATUS, "PROCESSING")
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .and(UPLOAD.PROCESSING_STATUS.eq("PENDING"))
            .execute() > 0

    fun getUploadForProcessing(
        tx: Configuration,
        uploadId: UUID,
    ): UploadForProcessing {
        val record =
            tx
                .dsl()
                .select(UPLOAD.ORIGINAL_FILENAME, UPLOAD.CHUNK_DATA, UPLOAD.SUBMISSION_ID)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
        val submissionId = record.get(UPLOAD.SUBMISSION_ID)!!
        val navEksternRefId =
            tx
                .dsl()
                .select(SUBMISSION.NAV_EKSTERN_REF_ID)
                .from(SUBMISSION)
                .where(SUBMISSION.ID.eq(submissionId))
                .fetchSingle()
                .get(SUBMISSION.NAV_EKSTERN_REF_ID)!!
        return UploadForProcessing(
            filename = record.get(UPLOAD.ORIGINAL_FILENAME)!!,
            chunkData = record.get(UPLOAD.CHUNK_DATA)!!,
            submissionId = submissionId,
            navEksternRefId = navEksternRefId,
        )
    }

    fun setFilId(
        tx: Configuration,
        uploadId: UUID,
        filId: UUID,
        mellomlagringFilnavn: String,
        mellomlagringStorrelse: Long,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.FIL_ID, filId)
            .set(UPLOAD.MELLOMLAGRING_FILNAVN, mellomlagringFilnavn)
            .set(UPLOAD.MELLOMLAGRING_STORRELSE, mellomlagringStorrelse)
            .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
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
            .set(UPLOAD.PROCESSING_STATUS, "FAILED")
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    /**
     * Finds uploads stuck in PROCESSING since before [cutoff] and marks them FAILED.
     * Returns the submission IDs that need SSE notification.
     */
    fun markStaleProcessingAsFailed(
        tx: Configuration,
        cutoff: java.time.OffsetDateTime,
    ): List<UUID> =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, "FAILED")
            .setNull(UPLOAD.CHUNK_DATA)
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(UPLOAD.PROCESSING_STATUS.eq("PROCESSING"))
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.SUBMISSION_ID)
            .fetch()
            .mapNotNull { it.get(UPLOAD.SUBMISSION_ID) }

    /**
     * Finds PENDING uploads that have received at least one chunk but stalled since before [cutoff].
     * Clears chunk_data and marks them FAILED.
     * Returns the submission IDs that need SSE notification.
     */
    fun markHaltedPendingAsFailed(
        tx: Configuration,
        cutoff: OffsetDateTime,
    ): List<UUID> =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, "FAILED")
            .setNull(UPLOAD.CHUNK_DATA)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.PROCESSING_STATUS.eq("PENDING"))
            .and(UPLOAD.UPLOAD_OFFSET.gt(0L))
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.SUBMISSION_ID)
            .fetch()
            .mapNotNull { it.get(UPLOAD.SUBMISSION_ID) }

    fun clearChunkData(
        tx: Configuration,
        uploadId: UUID,
    ) {
        tx
            .dsl()
            .update(UPLOAD)
            .setNull(UPLOAD.CHUNK_DATA)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    fun getUpload(
        tx: Configuration,
        uploadId: UUID,
    ) = tx
        .dsl()
        .select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, UPLOAD.FIL_ID, SUBMISSION.NAV_EKSTERN_REF_ID, UPLOAD.MELLOMLAGRING_FILNAVN, UPLOAD.SIZE, UPLOAD.MELLOMLAGRING_STORRELSE)
        .from(UPLOAD)
        .join(SUBMISSION).on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
        .where(UPLOAD.ID.eq(uploadId))
        .fetchSingle()
        .let {
            UploadWithFilename(
                id = it.get(UPLOAD.ID),
                originalFilename = it.get(UPLOAD.ORIGINAL_FILENAME),
                errors = emptyList(),
                filId = it.get(UPLOAD.FIL_ID),
                navEksternRefId = it.get(SUBMISSION.NAV_EKSTERN_REF_ID),
                mellomlagringFilnavn = it.get(UPLOAD.MELLOMLAGRING_FILNAVN),
                fileSize = it.get(UPLOAD.SIZE),
                mellomlagringStorrelse = it.get(UPLOAD.MELLOMLAGRING_STORRELSE),
            )
        }

    fun getUploadsWithFilenames(
        tx: Configuration,
        submissionId: UUID,
    ): List<UploadWithFilename> =
        tx
            .dsl()
            .select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, ERROR.CODE, UPLOAD.FIL_ID, SUBMISSION.NAV_EKSTERN_REF_ID, UPLOAD.MELLOMLAGRING_FILNAVN, UPLOAD.SIZE, UPLOAD.MELLOMLAGRING_STORRELSE)
            .from(UPLOAD)
            .leftJoin(ERROR)
            .on(ERROR.UPLOAD.eq(UPLOAD.ID))
            .join(SUBMISSION)
            .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(UPLOAD.SUBMISSION_ID.eq(submissionId))
            .fetch()
            .groupBy { it.get(UPLOAD.ID) }
            .map { (id, records) ->
                UploadWithFilename(
                    id = id,
                    originalFilename = records.first().get(UPLOAD.ORIGINAL_FILENAME),
                    errors = records.mapNotNull { it.get(ERROR.CODE) }.map { ValidationCode.valueOf(it) },
                    filId = records.first().get(UPLOAD.FIL_ID),
                    navEksternRefId = records.first().get(SUBMISSION.NAV_EKSTERN_REF_ID),
                    mellomlagringFilnavn = records.first().get(UPLOAD.MELLOMLAGRING_FILNAVN),
                    fileSize = records.first().get(UPLOAD.SIZE),
                    mellomlagringStorrelse = records.first().get(UPLOAD.MELLOMLAGRING_STORRELSE),
                )
            }

    fun deleteUpload(
        tx: Configuration,
        uploadId: UUID,
    ): Int {
        val submissionId = getSubmissionIdFromUploadId(tx, uploadId) ?: error("No submissionId for upload")
        val deleted =
            tx
                .dsl()
                .delete(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .execute()
        tx.dsl().execute("SELECT pg_notify('submission_update', ?)", submissionId.toString())
        return deleted
    }

    fun addErrors(
        tx: Configuration,
        uploadId: UUID,
        validations: List<Validation>,
    ) {
        validations.forEach {
            tx
                .dsl()
                .insertInto(ERROR)
                .set(ERROR.UPLOAD, uploadId)
                .set(ERROR.CODE, it.code.name)
                .set(ERROR.ID, UUID.randomUUID())
                .execute()
        }
        markFailed(tx, uploadId)
        notifyChange(tx, uploadId)
    }
}


