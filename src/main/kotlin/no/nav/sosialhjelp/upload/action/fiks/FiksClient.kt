package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.append
import io.ktor.serialization.jackson.jackson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.FileBoio
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
            install(ContentNegotiation) {
                json()
            }
        }
    }

    suspend fun uploadEttersendelse(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
        files: List<FileBoio>,
        metadata: Metadata,
        token: String,
    ): HttpResponse = client.submitFormWithBinaryData(ettersendelseUrl(fiksDigisosId, kommunenummer, navEksternRefId), formData {
        files.forEachIndexed { index, file ->
            val spesifikasjon = VedleggSpesifikasjon(
                type = metadata.type,
                tilleggsinfo = metadata.tilleggsinfo,
                innsendelsesfrist = metadata.innsendelsesfrist,
                hendelsetype = metadata.hendelsetype,
                hendelsereferanse = metadata.hendelsereferanse,
            )
            append("vedleggSpesifikasjon:$index", Json.encodeToString(spesifikasjon), Headers.build {
                append(HttpHeaders.ContentType, ContentType.Application.Json)
                append(HttpHeaders.ContentDisposition, ContentDisposition.parse("form-data").withParameter("name", "vedleggSpesifikasjon:$index"))
            })
            append("dokument:$index", file.file, Headers.build {
                append(HttpHeaders.ContentType, file.contentType)
                append(HttpHeaders.ContentDisposition, ContentDisposition.parse("form-data").withParameter("filename", file.filename))
            })
        }
    }) {
        bearerAuth(token)
    }


    suspend fun getSak(id: String): DigisosSak = jacksonClient.request(digisosSakUrl(id)).body()
}
