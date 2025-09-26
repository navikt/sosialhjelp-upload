package no.nav.sosialhjelp.upload.action

import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import java.security.Security

data class Upload(
    val file: ByteArray,
    val filename: String,
    val fileType: String,
)

private const val COUNTER_SUFFIX_LENGTH = 4

class DownstreamUploadService(
    private val fiksClient: FiksClient,
    private val kryptering: CMSKrypteringImpl,
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

    /**
     * returnerer neste id-suffix som 4-sifret String
     */
    private fun lagIdSuffix(previousId: String): String {
        val suffix = previousId.takeLast(COUNTER_SUFFIX_LENGTH).toLong() + 1
        return suffix.toString().padStart(4, '0')
    }

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        files: List<Upload>,
        token: String,
    ): HttpResponse {
        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId = lagNavEksternRefId(sak)

        // TODO: Kryptering
        val cert = fiksClient.fetchPublicKey()

        val encrypted =
            files.map { file ->
                file.copy(file = kryptering.krypterData(file.file, cert, Security.getProvider("BC")))
            }

        // TODO: Ta med ettersendelse.pdf i opplastingen
        return fiksClient.uploadEttersendelse(fiksDigisosId, kommunenummer, navEksternRefId, encrypted, metadata, token)
    }
}

@Serializable
data class VedleggSpesifikasjon(
    val type: String,
    val tilleggsinfo: String?,
    val innsendelsesfrist: String?,
    val hendelsetype: String?,
    val hendelsereferanse: String?,
)
