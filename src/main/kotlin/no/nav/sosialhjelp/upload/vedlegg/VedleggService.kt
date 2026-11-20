package no.nav.sosialhjelp.upload.vedlegg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.submission.MellomlagringReconciler
import no.nav.sosialhjelp.upload.submission.toDtos
import no.nav.sosialhjelp.upload.upload.SubmissionDeletionService
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.SubmissionFilesMissingException
import no.nav.sosialhjelp.upload.validation.SubmissionUploadsInProgressException
import org.jooq.DSLContext

class VedleggService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val submissionDeletionService: SubmissionDeletionService,
    private val mellomlagringReconciler: MellomlagringReconciler,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Deletes every submission for [navEksternRefId], including the files in Fiks mellomlagring
     * unless [keepMellomlagring] is true. Called by sosialhjelp-soknad-api once the søknad has been sent.
     */
    suspend fun deleteVedlegg(
        navEksternRefId: String,
        keepMellomlagring: Boolean,
    ): Int = submissionDeletionService.deleteByNavEksternRefId(navEksternRefId, keepMellomlagring = keepMellomlagring)

    /**
     * Deletes the submission for [navEksternRefId] + [kategori], including the files in Fiks
     * mellomlagring. Leaving the files behind would make them orphans that Fiks later rejects the
     * innsending over.
     */
    suspend fun deleteVedlegg(
        navEksternRefId: String,
        kategori: String,
    ): Int =
        submissionDeletionService.deleteByNavEksternRefId(
            navEksternRefId,
            keepMellomlagring = false,
            kategori = kategori,
        )

    suspend fun getVedleggByNavEksternRefId(navEksternRefId: String): VedleggSpesifikasjon {
        // soknad-api calls this just before it submits. Mellomlagring must match the DB first.
        when (val result = mellomlagringReconciler.reconcile(navEksternRefId)) {
            MellomlagringReconciler.Result.Clean -> {}
            MellomlagringReconciler.Result.InProgress -> throw SubmissionUploadsInProgressException()
            is MellomlagringReconciler.Result.Missing -> throw SubmissionFilesMissingException(result.toDtos())
        }
        val uploads =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    uploadRepository.getCompletedUploadsByNavEksternRefId(tx, navEksternRefId)
                }
            }

        val grouped = uploads.groupBy { it.category }

        val vedleggList =
            grouped.map { (key, files) ->
                Vedlegg(
                    kategori = key,
                    filer =
                        files.map { file ->
                            Fil(
                                filnavn = file.mellomlagringFilnavn,
                                sha512 = file.sha512,
                            )
                        },
                )
            }

        return VedleggSpesifikasjon(vedlegg = vedleggList)
    }
}
