package no.nav.sosialhjelp.upload.vedlegg

import no.nav.sosialhjelp.upload.database.UploadRepository
import org.jooq.DSLContext
import java.util.UUID

class VedleggService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
) {
    fun setDocumentCategory(uploadId: UUID, kategori: String?) {
        dsl.transaction { tx ->
            uploadRepository.setCategory(tx, uploadId, kategori)
        }
    }

    fun getVedleggByNavEksternRefId(navEksternRefId: String): VedleggSpesifikasjon {
        val uploads = dsl.transactionResult { tx ->
            uploadRepository.getCompletedUploadsByNavEksternRefId(tx, navEksternRefId)
        }

        val grouped = uploads.groupBy { it.category }

        val vedleggList = grouped.map { (key, files) ->
            Vedlegg(
                kategori = key,
                filer = files.map { file ->
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
