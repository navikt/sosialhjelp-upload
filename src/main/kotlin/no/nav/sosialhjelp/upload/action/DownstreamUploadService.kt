package no.nav.sosialhjelp.upload.action

import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import org.jooq.DSLContext
import java.util.UUID

private const val COUNTER_SUFFIX_LENGTH = 4

class DownstreamUploadService(
    private val fiksClient: FiksClient,
    private val dsl: DSLContext,
    private val documentRepository: DocumentRepository,
    private val uploadRepository: UploadRepository,
    private val notificationService: DocumentNotificationService,
) {
    private fun lagNavEksternRefId(digisosSak: DigisosSak): String {
        val previousId: String =
            digisosSak.ettersendtInfoNAV
                ?.ettersendelser
                ?.map { it.navEksternRefId }
                ?.maxByOrNull { it.takeLast(COUNTER_SUFFIX_LENGTH).toLong() }
                ?: digisosSak.originalSoknadNAV?.navEksternRefId?.plus("0000")
                ?: digisosSak.fiksDigisosId.plus("0000")

        val nesteSuffix = lagIdSuffix(previousId)
        return (previousId.dropLast(COUNTER_SUFFIX_LENGTH).plus(nesteSuffix))
    }

    private fun lagIdSuffix(previousId: String): String {
        val suffix = previousId.takeLast(COUNTER_SUFFIX_LENGTH).toLong() + 1
        return suffix.toString().padStart(4, '0')
    }

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        documentId: UUID,
    ): Boolean {
        val uploads =
            dsl.transactionResult { tx ->
                uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
            }

        val validUploads = uploads.filter { it.errors.isEmpty() && it.filId != null }

        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId = lagNavEksternRefId(sak)

        val filReferanser =
            validUploads.map { upload ->
                FilReferanse(
                    filnavn = upload.originalFilename!!,
                    filId = upload.filId!!,
                    mellomlagringRefId = upload.mellomlagringRefId!!,
                    storrelse = upload.fileSize ?: 0L,
                )
            }

        val response =
            fiksClient.uploadEttersendelse(
                fiksDigisosId,
                kommunenummer,
                navEksternRefId,
                filReferanser,
                metadata,
                token,
            )

        if (response.status.isSuccess()) {
            dsl.transactionResult { tx ->
                documentRepository.cleanup(tx, documentId)
            }
            notificationService.notifyUpdate(documentId)
            return true
        }
        return false
    }
}

data class FilReferanse(
    val filnavn: String,
    val filId: UUID,
    val mellomlagringRefId: String,
    val storrelse: Long,
)

@Serializable
data class VedleggSpesifikasjon(
    val type: String,
    val tilleggsinfo: String?,
    val innsendelsesfrist: String?,
    val hendelsetype: String?,
    val hendelsereferanse: String?,
)
