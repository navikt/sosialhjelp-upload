package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.validation.Validation
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.*
import kotlin.collections.component1
import kotlin.collections.component2

data class Upload(
    val id: UUID?,
    val originalFilename: String?,
    val errors: List<ValidationCode>,
    val filId: UUID?,
    val navEksternRefId: String?,
    val mellomlagringFilnavn: String?,
    val fileSize: Long?,
    val mellomlagringStorrelse: Long?,
    val status: Status,
    val sha512: String? = null,
)

enum class Status {
    PROCESSING, FAILED, PENDING, COMPLETE
}

data class UploadForProcessing(
    val filename: String,
    val gcsKey: String,
    val submissionId: UUID,
    val navEksternRefId: String,
)

class UploadRepository {
    class OffsetMismatchException(message: String) : RuntimeException(message)

    data class StaleUploadInfo(val submissionId: UUID, val gcsKey: String?)

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
            ?.also { notifyChange(tx, it) }
    }

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
        val affected = tx.dsl().update(UPLOAD)
            .set(UPLOAD.UPLOAD_OFFSET, newOffset)
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
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
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .and(UPLOAD.PROCESSING_STATUS.eq(Status.PENDING.name))
            .execute() > 0

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
            gcsKey = record.get(UPLOAD.GCS_KEY)!!,
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
            .set(UPLOAD.UPDATED_AT, java.time.OffsetDateTime.now())
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    /**
     * Finds uploads stuck in PROCESSING since before [cutoff] and marks them FAILED.
     * Returns info needed for SSE notification and GCS cleanup.
     */
    fun markStaleProcessingAsFailed(
        tx: Configuration,
        cutoff: java.time.OffsetDateTime,
    ): List<StaleUploadInfo> =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.PROCESSING_STATUS.eq(Status.PROCESSING.name))
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let { StaleUploadInfo(it, record.get(UPLOAD.GCS_KEY)) }
            }

    /**
     * Finds PENDING uploads that have received at least one chunk but stalled since before [cutoff].
     * Marks them FAILED. Returns info needed for SSE notification and GCS cleanup.
     */
    fun markHaltedPendingAsFailed(
        tx: Configuration,
        cutoff: OffsetDateTime,
    ): List<StaleUploadInfo> =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.PROCESSING_STATUS.eq(Status.PENDING.name))
            .and(UPLOAD.UPLOAD_OFFSET.gt(0L))
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let { StaleUploadInfo(it, record.get(UPLOAD.GCS_KEY)) }
            }

    fun getUpload(
        tx: Configuration,
        uploadId: UUID,
    ) = tx
        .dsl()
        .select(UPLOAD.ID, UPLOAD.ORIGINAL_FILENAME, UPLOAD.FIL_ID, SUBMISSION.NAV_EKSTERN_REF_ID, UPLOAD.MELLOMLAGRING_FILNAVN, UPLOAD.SIZE, UPLOAD.MELLOMLAGRING_STORRELSE, UPLOAD.PROCESSING_STATUS)
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
                status = it.get(UPLOAD.PROCESSING_STATUS)?.let { status -> Status.valueOf(status) } ?: error("No processing status. Was it not selected?")
            )
        }

    fun getUploads(
        tx: Configuration,
        submissionId: UUID,
    ): List<Upload> =
        tx
            .dsl()
            .select(
                UPLOAD.ID,
                UPLOAD.ORIGINAL_FILENAME,
                ERROR.CODE,
                UPLOAD.FIL_ID,
                SUBMISSION.NAV_EKSTERN_REF_ID,
                UPLOAD.MELLOMLAGRING_FILNAVN,
                UPLOAD.SIZE,
                UPLOAD.MELLOMLAGRING_STORRELSE,
                UPLOAD.PROCESSING_STATUS,
                UPLOAD.SHA512
            )
            .from(UPLOAD)
            .leftJoin(ERROR)
            .on(ERROR.UPLOAD.eq(UPLOAD.ID))
            .join(SUBMISSION)
            .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(UPLOAD.SUBMISSION_ID.eq(submissionId))
            .fetch()
            .groupBy { it.get(UPLOAD.ID) }
            .map { (id, records) ->
                Upload(
                    id = id,
                    originalFilename = records.first().get(UPLOAD.ORIGINAL_FILENAME),
                    errors = records.mapNotNull { it.get(ERROR.CODE) }.map { ValidationCode.valueOf(it) },
                    filId = records.first().get(UPLOAD.FIL_ID),
                    navEksternRefId = records.first().get(SUBMISSION.NAV_EKSTERN_REF_ID),
                    mellomlagringFilnavn = records.first().get(UPLOAD.MELLOMLAGRING_FILNAVN),
                    fileSize = records.first().get(UPLOAD.SIZE),
                    mellomlagringStorrelse = records.first().get(UPLOAD.MELLOMLAGRING_STORRELSE),
                    status = records.first().get(UPLOAD.PROCESSING_STATUS)?.let { Status.valueOf(it) } ?: error("No processing status. Was it not selected?"),
                    sha512 = records.first().get(UPLOAD.SHA512)
                )
            }

    fun getGcsKeysForSubmission(
        tx: Configuration,
        submissionId: UUID,
    ): List<String> =
        tx
            .dsl()
            .select(UPLOAD.GCS_KEY)
            .from(UPLOAD)
            .where(UPLOAD.SUBMISSION_ID.eq(submissionId))
            .and(UPLOAD.GCS_KEY.isNotNull)
            .fetch()
            .mapNotNull { it.get(UPLOAD.GCS_KEY) }

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


