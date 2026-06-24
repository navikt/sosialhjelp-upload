package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.Configuration
import java.util.UUID

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
    val kategori: String? = null,
)

enum class Status {
    PROCESSING, FAILED, PENDING, COMPLETE
}

data class UploadForProcessing(
    val filename: String,
    val gcsKey: String,
    val submissionId: UUID,
    val navEksternRefId: String,
    val fiksDigisosId: String?,
)

data class UploadForVedlegg(
    val category: String?,
    val mellomlagringFilnavn: String,
    val sha512: String?,
)

/**
 * Cross-cutting upload read queries used by status, vedlegg and retention features.
 */
class UploadRepository {

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
                UPLOAD.SHA512,
                SUBMISSION.KATEGORI,
            )
            .from(UPLOAD)
            .leftJoin(ERROR).on(ERROR.UPLOAD.eq(UPLOAD.ID))
            .join(SUBMISSION).on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
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
                    status = records.first().get(UPLOAD.PROCESSING_STATUS)
                        ?.let { Status.valueOf(it) }
                        ?: error("No processing status. Was it not selected?"),
                    sha512 = records.first().get(UPLOAD.SHA512),
                    kategori = records.first().get(SUBMISSION.KATEGORI),
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

    fun getCompletedUploadsByNavEksternRefId(
        tx: Configuration,
        navEksternRefId: String,
    ): List<UploadForVedlegg> =
        tx
            .dsl()
            .select(
                SUBMISSION.KATEGORI,
                UPLOAD.MELLOMLAGRING_FILNAVN,
                UPLOAD.SHA512,
            )
            .from(UPLOAD)
            .join(SUBMISSION).on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
            .where(SUBMISSION.NAV_EKSTERN_REF_ID.eq(navEksternRefId))
            .and(UPLOAD.PROCESSING_STATUS.eq(Status.COMPLETE.name))
            .fetch()
            .map {
                UploadForVedlegg(
                    category = it.get(SUBMISSION.KATEGORI),
                    mellomlagringFilnavn = it.get(UPLOAD.MELLOMLAGRING_FILNAVN)!!,
                    sha512 = it.get(UPLOAD.SHA512),
                )
            }
}
