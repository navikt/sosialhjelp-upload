package no.nav.sosialhjelp.upload.action

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient

class FileBoio(
    val file: ByteArray,
    val filename: String,
    val contentType: ContentType,
)

private const val COUNTER_SUFFIX_LENGTH = 4

class DownstreamUploadService(val fiksClient: FiksClient) {
    val client =
        HttpClient(CIO) {
            expectSuccess = false
        }

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

    suspend fun upload2(
        metadata: Metadata,
        fiksDigisosId: String,
        files: List<FileBoio>,
        token: String,
    ): HttpResponse {
        val sak = fiksClient.getSak(fiksDigisosId)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId = lagNavEksternRefId(sak)
        return fiksClient.uploadEttersendelse(fiksDigisosId, kommunenummer, navEksternRefId, files, metadata, token)
    }


    suspend fun upload(
        url: String,
        bytes: ByteArray,
        filename: String,
    ) = client.submitFormWithBinaryData(url, formData {
        append(
            "file",
            bytes,
            Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                append(HttpHeaders.ContentDisposition, "filename=$filename")
            },
        )
    })
}

@Serializable
data class VedleggSpesifikasjon(val type: String, val tilleggsinfo: String?, val innsendelsesfrist: String?, val hendelsetype: String?, val hendelsereferanse: String?)
