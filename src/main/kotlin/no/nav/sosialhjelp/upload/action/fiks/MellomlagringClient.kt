package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.FormPart
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.utils.io.ByteReadChannel
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.upload.texas.TexasClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class MellomlagringClient(
    @Property("fiks.baseUrl") private val fiksBaseUrl: String,
    @Property("fiks.integrasjonsid") private val integrasjonsid: String?,
    @Property("fiks.integrasjonspassord") private val integrasjonspassord: String?,
    private val meterRegistry: MeterRegistry,
    private val texasClient: TexasClient,
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

    suspend fun getFile(navEksternRefId: String, id: UUID): ByteArray {
        val response = client.get(mellomlagringUrl(navEksternRefId) + "/$id") {
            headers {
                integrasjonsid?.let { append("IntegrasjonId", it) } ?: run {
                    logger.warn("Mangler fiks integrasjonsid")
                }
                integrasjonspassord?.let { append("IntegrasjonPassord", it) } ?: run {
                    logger.warn("Mangler fiks integrasjonspassord")
                }
            }
            accept(ContentType.Any)
            bearerAuth(texasClient.getMaskinportenToken())
        }
        check(response.status.isSuccess()) { "Fikk feil fra Fiks på GET /mellomlagring/$navEksternRefId/$id: ${response.bodyAsText()}" }
        return response.bodyAsBytes()
    }

    companion object {
        private val RETRY_DELAYS_MS = listOf(500L, 2000L)
        val MAX_ATTEMPTS = RETRY_DELAYS_MS.size + 1
    }

    suspend fun uploadFile(
        navEksternRefId: String,
        filename: String,
        contentType: String,
        data: ByteArray,
    ): UUID {
        var lastException: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return uploadFileAttempt(navEksternRefId, filename, contentType, data)
            } catch (e: Exception) {
                lastException = e
                if (attempt < MAX_ATTEMPTS) {
                    val delayMs = RETRY_DELAYS_MS[attempt - 1]
                    logger.warn(
                        "Mellomlagring upload attempt $attempt/$MAX_ATTEMPTS failed for $navEksternRefId, retrying in ${delayMs}ms",
                        e,
                    )
                    meterRegistry.counter("mellomlagring.upload.retry").increment()
                    delay(delayMs.milliseconds)
                }
            }
        }
        throw lastException!!
    }

    private suspend fun uploadFileAttempt(
        navEksternRefId: String,
        filename: String,
        contentType: String,
        data: ByteArray,
    ): UUID =
        withContext(Dispatchers.IO) {
            val startTime = System.nanoTime()
            val metadataPart = FormPart(
                key = "metadata",
                value = Json.encodeToString(MellomlagringMetadata(filename, data.size.toLong(), contentType)),
                headers = Headers.build { append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) },
            )
            val filePart = FormPart(
                key = "files",
                value = ChannelProvider { ByteReadChannel(data) },
                headers = Headers.build {
                    append(HttpHeaders.ContentType, contentType)
                    append(HttpHeaders.ContentDisposition, """filename="$filename"""")
                },
            )
            val formData =
                formData {
                    append(metadataPart)
                    append(filePart)
                }
            val response =
                client.submitFormWithBinaryData(mellomlagringUrl(navEksternRefId), formData) {
                    headers {
                        integrasjonsid?.let { append("IntegrasjonId", it) }
                        integrasjonspassord?.let { append("IntegrasjonPassord", it) }
                    }
                    contentType(ContentType.MultiPart.FormData)
                    bearerAuth(texasClient.getMaskinportenToken())
                }

            check(response.status.isSuccess()) {
                "Mellomlagring upload failed: ${response.status}. Body: ${response.bodyAsText()}"
            }

            val mellomlagring = response.body<MellomlagringResponse>()
            val filId = mellomlagring.mellomlagringMetadataList
                .firstOrNull()
                ?.filId
                ?.let { UUID.fromString(it) }
                ?: error("No filId returned from mellomlagring for upload to $navEksternRefId")
            meterRegistry.timer("mellomlagring.upload").record(Duration.ofNanos(System.nanoTime() - startTime))
            filId
        }

    suspend fun deleteFile(
        navEksternRefId: String,
        filId: UUID,
    ) {
        withContext(Dispatchers.IO) {
            val response =
                client.delete("${mellomlagringUrl(navEksternRefId)}/$filId") {
                    headers {
                        integrasjonsid?.let { append("IntegrasjonId", it) }
                        integrasjonspassord?.let { append("IntegrasjonPassord", it) }
                    }
                    bearerAuth(texasClient.getMaskinportenToken())
                }
            if (response.status != HttpStatusCode.NoContent) {
                logger.warn("Failed to delete file $filId from mellomlagring: ${response.status}")
            }
        }
    }

    suspend fun deleteMellomlagring(navEksternRefId: String) {
        withContext(Dispatchers.IO) {
            val response =
                client.delete(mellomlagringUrl(navEksternRefId)) {
                    headers {
                        integrasjonsid?.let { append("IntegrasjonId", it) }
                        integrasjonspassord?.let { append("IntegrasjonPassord", it) }
                    }
                    bearerAuth(texasClient.getMaskinportenToken())
                }
            if (response.status != HttpStatusCode.NoContent) {
                logger.warn("Failed to delete mellomlagring for $navEksternRefId: ${response.status}")
            }
        }
    }
}

@Serializable
private data class MellomlagringMetadata(
    val filnavn: String,
    val storrelse: Long,
    val mimetype: String,
)

@Serializable
private data class MellomlagringResponse(
    val navEksternRefId: String,
    val mellomlagringMetadataList: List<MellomlagringFilInfo> = emptyList(),
)

@Serializable
data class MellomlagringFilInfo(
    val filnavn: String,
    val filId: String,
    val storrelse: Long,
    val mimetype: String,
)
