package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB queries for the upload recovery job: finding and marking stale uploads as failed.
 */
class UploadRecoveryQueries {
    data class StaleUploadInfo(
        val uploadId: UUID,
        val submissionId: UUID,
        val gcsKey: String?,
        val navEksternRefId: String?,
    )

    /**
     * Finds uploads stuck in PROCESSING since before [cutoff] and marks them FAILED.
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
            .and(UPLOAD.UPDATED_AT.lt(cutoff))
            .returning(UPLOAD.ID, UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let {
                    StaleUploadInfo(
                        record.get(UPLOAD.ID)!!,
                        it,
                        record.get(UPLOAD.GCS_KEY),
                        tx
                            .dsl()
                            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
                            .from(SUBMISSION)
                            .where(SUBMISSION.ID.eq(it))
                            .fetchSingle(SUBMISSION.NAV_EKSTERN_REF_ID),
                    )
                }
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
            .returning(UPLOAD.ID, UPLOAD.SUBMISSION_ID, UPLOAD.GCS_KEY)
            .fetch()
            .mapNotNull { record ->
                record.get(UPLOAD.SUBMISSION_ID)?.let {
                    StaleUploadInfo(
                        record.get(UPLOAD.ID)!!,
                        it,
                        record.get(UPLOAD.GCS_KEY),
                        tx
                            .dsl()
                            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
                            .from(SUBMISSION)
                            .where(SUBMISSION.ID.eq(it))
                            .fetchSingle(SUBMISSION.NAV_EKSTERN_REF_ID),
                    )
                }
            }
}
