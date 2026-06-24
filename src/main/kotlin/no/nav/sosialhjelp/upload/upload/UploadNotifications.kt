package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import java.util.UUID

/**
 * Shared helper for sending Postgres NOTIFY on upload state changes.
 * Used by both TUS and processing query classes so the notification
 * logic lives in one place.
 */
object UploadNotifications {
    fun notifyChange(
        tx: Configuration,
        uploadId: UUID,
    ) {
        val submissionId = getSubmissionIdFromUploadId(tx, uploadId) ?: return
        // Send NOTIFY within the transaction so it fires atomically on commit
        tx.dsl().execute("SELECT pg_notify('submission_update', ?)", submissionId.toString())
    }

    private fun getSubmissionIdFromUploadId(
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
}
