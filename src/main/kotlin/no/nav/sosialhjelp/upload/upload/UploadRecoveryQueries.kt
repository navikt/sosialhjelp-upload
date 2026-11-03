package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB queries for the upload recovery job: finding and marking stale uploads as failed.
 */
class UploadRecoveryQueries {
    data class StaleUploadInfo(
        val submissionId: UUID,
        val gcsKey: String?,
    )

    /**
     * Fails uploads whose last allowed attempt has an expired lease. Uploads with attempts left
     * are retried by [ProcessingWorker] instead.
     * Returns info needed for SSE notification and GCS cleanup.
     */
    fun markStaleProcessingAsFailed(
        tx: Configuration,
        cutoff: OffsetDateTime,
    ): List<StaleUploadInfo> =
        tx
            .dsl()
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.FAILED.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .where(UPLOAD.PROCESSING_STATUS.eq(Status.PROCESSING.name))
            .and(UPLOAD.PROCESSING_STARTED_AT.lt(cutoff))
            .and(UPLOAD.PROCESSING_ATTEMPTS.ge(MAX_PROCESSING_ATTEMPTS))
            .returning(UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let { StaleUploadInfo(it, record.get(UPLOAD.GCS_KEY)) }
            }

    /**
     * Finds PENDING uploads that have stalled since before [cutoff].
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
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let { StaleUploadInfo(it, record.get(UPLOAD.GCS_KEY)) }
            }
}
