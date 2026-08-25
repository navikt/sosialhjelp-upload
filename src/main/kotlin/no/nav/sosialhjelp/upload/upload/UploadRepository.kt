package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.Configuration
import org.jooq.impl.DSL
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
    val correlationId: UUID? = null,
)

enum class Status {
    PROCESSING,
    FAILED,
    PENDING,
    COMPLETE,
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
 * The remote resources belonging to a single submission, needed to delete it completely.
 */
data class SubmissionResources(
    val submissionId: UUID,
    val navEksternRefId: String?,
    val kategori: String?,
    val filIds: List<UUID>,
    val gcsKeys: List<String>,
)

class UploadRepository {
    /**
     * Returns the ids of all submissions for [navEksternRefId], optionally narrowed to [kategori].
     *
     * Note that several submissions can share a navEksternRefId — a søknad has one submission per
     * kategori. Any deletion must therefore be scoped per submission, never per navEksternRefId.
     */
    fun findSubmissionIds(
        tx: Configuration,
        navEksternRefId: String,
        kategori: String? = null,
    ): List<UUID> =
        tx
            .dsl()
            .select(SUBMISSION.ID)
            .from(SUBMISSION)
            .where(SUBMISSION.NAV_EKSTERN_REF_ID.eq(navEksternRefId))
            .and(kategori?.let { SUBMISSION.KATEGORI.eq(it) } ?: DSL.noCondition())
            .fetch()
            .mapNotNull { it.get(SUBMISSION.ID) }

    /**
     * Collects the remote resources owned by [submissionId] so they can be deleted individually
     * after the DB row is gone.
     */
    fun getSubmissionResources(
        tx: Configuration,
        submissionId: UUID,
    ): SubmissionResources? {
        val records =
            tx
                .dsl()
                .select(
                    SUBMISSION.NAV_EKSTERN_REF_ID,
                    SUBMISSION.KATEGORI,
                    UPLOAD.FIL_ID,
                    UPLOAD.GCS_KEY,
                ).from(SUBMISSION)
                .leftJoin(UPLOAD)
                .on(UPLOAD.SUBMISSION_ID.eq(SUBMISSION.ID))
                .where(SUBMISSION.ID.eq(submissionId))
                .fetch()

        if (records.isEmpty()) return null

        return SubmissionResources(
            submissionId = submissionId,
            navEksternRefId = records.first().get(SUBMISSION.NAV_EKSTERN_REF_ID),
            kategori = records.first().get(SUBMISSION.KATEGORI),
            filIds = records.mapNotNull { it.get(UPLOAD.FIL_ID) },
            gcsKeys = records.mapNotNull { it.get(UPLOAD.GCS_KEY) },
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
                UPLOAD.SHA512,
                SUBMISSION.KATEGORI,
                UPLOAD.CORRELATION_ID,
            ).from(UPLOAD)
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
                    status =
                        records
                            .first()
                            .get(UPLOAD.PROCESSING_STATUS)
                            ?.let { Status.valueOf(it) }
                            ?: error("No processing status. Was it not selected?"),
                    sha512 = records.first().get(UPLOAD.SHA512),
                    kategori = records.first().get(SUBMISSION.KATEGORI),
                    correlationId = records.first().get(UPLOAD.CORRELATION_ID),
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
            ).from(UPLOAD)
            .join(SUBMISSION)
            .on(SUBMISSION.ID.eq(UPLOAD.SUBMISSION_ID))
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
