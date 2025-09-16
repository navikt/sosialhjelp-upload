package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.di.annotations.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.Upload
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.action.VedleggSpesifikasjon

class FiksClient(@Property("fiksBaseUrl") val fiksBaseUrl: String) {
    private fun ettersendelseUrl(fiksDigisosId: String, kommunenummer: String, navEksternRefId: String) =
        "${fiksBaseUrl}/digisos/api/v1/soknader/${kommunenummer}/${fiksDigisosId}/${navEksternRefId}"

    private fun digisosSakUrl(fiksDigisosId: String) = "${fiksBaseUrl}/digisos/api/v1/soknader/${fiksDigisosId}"


    private val jacksonClient by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                jackson()
            }
        }
    }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.BODY
            }
            install(ContentNegotiation) {
                json()
            }
        }
    }

    suspend fun uploadEttersendelse(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
        files: List<Upload>,
        metadata: Metadata,
        token: String,
    ): HttpResponse = client.submitFormWithBinaryData(ettersendelseUrl(fiksDigisosId, kommunenummer, navEksternRefId), formData {
        val vedleggJson = VedleggSpesifikasjon(
            type = metadata.type,
            tilleggsinfo = metadata.tilleggsinfo,
            innsendelsesfrist = metadata.innsendelsesfrist,
            hendelsetype = metadata.hendelsetype,
            hendelsereferanse = metadata.hendelsereferanse,
        )
        append("vedlegg.json", Json.encodeToString(vedleggJson), Headers.build {
            append(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
        })
        files.forEachIndexed { index, file ->
            val vedleggMetadata = VedleggMetadata(
                filnavn = file.filename,
                mimetype = file.fileType,
                storrelse = file.file.size.toLong(),
            )
            append("vedleggSpesifikasjon:$index", Json.encodeToString(vedleggMetadata), Headers.build {
                append(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
            })
            append("dokument:$index", file.file, Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.OctetStream)
                append(HttpHeaders.ContentDisposition, "filename=\"${file.filename}\"")
            })
        }
    }) {

        // TODO: Legg på integrasjonsid/-passord i header
        bearerAuth(token)
        contentType(ContentType.MultiPart.FormData)
    }


    suspend fun getSak(id: String): DigisosSak = jacksonClient.request(digisosSakUrl(id)).body()
}

@Serializable
private data class VedleggMetadata(
    val filnavn: String?,
    val mimetype: String?,
    val storrelse: Long,
)
