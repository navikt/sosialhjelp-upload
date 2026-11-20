package no.nav.sosialhjelp.upload.files

import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.upload.PipelineResult
import no.nav.sosialhjelp.upload.upload.Status
import org.jooq.Configuration
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
    }
}
