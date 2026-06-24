package no.nav.sosialhjelp.upload.vedlegg

import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext

class VedleggService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
) {
    fun getVedleggByNavEksternRefId(navEksternRefId: String): VedleggSpesifikasjon {
        val uploads =
            dsl.transactionResult { tx ->
                uploadRepository.getCompletedUploadsByNavEksternRefId(tx, navEksternRefId)
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
