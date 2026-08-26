package no.nav.sosialhjelp.upload.vedlegg

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.upload.SubmissionDeletionService
import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext

class VedleggService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val submissionDeletionService: SubmissionDeletionService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Deletes every submission for [navEksternRefId], including the files in Fiks mellomlagring.
     * Called by sosialhjelp-soknad-api once the søknad has been sent.
     */
    suspend fun deleteVedlegg(navEksternRefId: String): Int =
        submissionDeletionService.deleteByNavEksternRefId(navEksternRefId)

    /**
     * Deletes the submission for [navEksternRefId] + [kategori], including the files in Fiks
     * mellomlagring. Leaving the files behind would make them orphans that Fiks later rejects the
     * innsending over.
     */
    suspend fun deleteVedlegg(
        navEksternRefId: String,
        kategori: String,
    ): Int = submissionDeletionService.deleteByNavEksternRefId(navEksternRefId, kategori)

    suspend fun getVedleggByNavEksternRefId(navEksternRefId: String): VedleggSpesifikasjon {
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
