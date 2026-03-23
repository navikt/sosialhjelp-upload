package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.headers
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

class MellomlagringClient(
    @Property("fiks.baseUrl") private val fiksBaseUrl: String,
    @Property("fiks.integrasjonsid") private val integrasjonsid: String?,
    @Property("fiks.integrasjonspassord") private val integrasjonspassord: String?,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun mellomlagringUrl(navEksternRefId: String) =
        "$fiksBaseUrl/digisos/api/v1/mellomlagring/$navEksternRefId"

    suspend fun uploadFile(
        navEksternRefId: String,
        filename: String,
        contentType: String,
        data: ByteArray,
        token: String,
    ): UUID =
        withContext(Dispatchers.IO) {
            val formData =
                formData {
                    append(
                        filename,
                        ChannelProvider(data.size.toLong()) { ByteReadChannel(data) },
                        Headers.build {
                            append(HttpHeaders.ContentType, contentType)
                            append(HttpHeaders.ContentDisposition, """filename="$filename"""")
                        },
                    )
                }
            val response =
                client.submitFormWithBinaryData(mellomlagringUrl(navEksternRefId), formData) {
                    headers {
                        integrasjonsid?.let { append("IntegrasjonId", it) }
                        integrasjonspassord?.let { append("IntegrasjonPassord", it) }
                    }
                    bearerAuth(token)
                }

            check(response.status.isSuccess()) {
                "Mellomlagring upload failed: ${response.status}"
            }

            val mellomlagring = response.body<MellomlagringResponse>()
            mellomlagring.mellomlagringMetadataList
                .find { it.filnavn == filename }
                ?.filId
                ?.let { UUID.fromString(it) }
                ?: error("No filId returned for filename $filename from mellomlagring")
        }

    suspend fun deleteFile(
        navEksternRefId: String,
        filId: UUID,
        token: String,
    ) {
        withContext(Dispatchers.IO) {
            val response =
                client.delete("${mellomlagringUrl(navEksternRefId)}/$filId") {
                    headers {
                        integrasjonsid?.let { append("IntegrasjonId", it) }
                        integrasjonspassord?.let { append("IntegrasjonPassord", it) }
                    }
                    bearerAuth(token)
                }
            if (response.status != HttpStatusCode.NoContent) {
                logger.warn("Failed to delete file $filId from mellomlagring: ${response.status}")
            }
        }
    }
}

@Serializable
private data class MellomlagringResponse(
    val navEksternRefId: String,
    val mellomlagringMetadataList: List<MellomlagringFilInfo> = emptyList(),
)

@Serializable
private data class MellomlagringFilInfo(
    val filnavn: String? = null,
    val filId: String? = null,
    val storrelse: Long? = null,
    val mimetype: String? = null,
)
