package no.nav.sosialhjelp.upload.vedlegg

import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import org.jooq.DSLContext
import java.util.UUID

class VedleggService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val submissionRepository: SubmissionRepository,
) {
    fun setDocumentType(uploadId: UUID, type: String, tilleggsinfo: String?) {
        dsl.transaction { tx ->
            uploadRepository.setDocumentType(tx, uploadId, type, tilleggsinfo)
        }
    }

    fun getVedleggByNavEksternRefId(navEksternRefId: String): JsonVedleggSpesifikasjon {
        val uploads = dsl.transactionResult { tx ->
            uploadRepository.getCompletedUploadsByNavEksternRefId(tx, navEksternRefId)
        }

        // Group by (type, tilleggsinfo) — same grouping as DokumentasjonToJsonMapper
        val grouped = uploads.groupBy { it.documentType to it.tilleggsinfo }

        val vedleggList = grouped.map { (key, files) ->
            val (type, tilleggsinfo) = key
            // Mirror soknad-api: tilleggsinfo "annen" (UTGIFTER_ANDRE_UTGIFTER) → bruker, everything else → soknad
            val isBrukerType = tilleggsinfo == "annen"

            JsonVedlegg(
                type = type,
                tilleggsinfo = tilleggsinfo,
                status = "LastetOpp",
                filer = files.map { file ->
                    JsonFiler(
                        filnavn = file.mellomlagringFilnavn,
                        sha512 = file.sha512,
                    )
                },
                hendelseType = if (isBrukerType) "bruker" else "soknad",
                hendelseReferanse = if (isBrukerType) null else UUID.randomUUID().toString(),
            )
        }

        return JsonVedleggSpesifikasjon(vedlegg = vedleggList)
    }
}
