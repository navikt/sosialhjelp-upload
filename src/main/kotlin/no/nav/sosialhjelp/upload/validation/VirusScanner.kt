package no.nav.sosialhjelp.upload.validation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import org.slf4j.LoggerFactory

class VirusScanner(
    @Property("virus.scanner.url") val url: String,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    val httpClient =
        HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) {
                json()
            }
        }

    suspend fun scan(data: ByteArray): Result {
        try {
            if (url.isEmpty()) {
                meterRegistry.counter("virus.scan.skipped", "reason", "not_configured").increment()
                return Result.OK
            }
            val body =
                httpClient
                    .put(url) {
                        contentType(ContentType.Application.OctetStream)
                        setBody(data)
                    }.body<List<ScanResult>>()
            if (body.all { it.result == Result.OK }) {
                return Result.OK
            }
            return Result.FOUND
        } catch (e: Exception) {
            logger.warn("Virus scanner error: ${e.message}, assuming file is clean", e)
            meterRegistry.counter("virus.scan.skipped", "reason", "error").increment()
            return Result.OK
        }
    }
}

enum class Result {
    FOUND,
    OK,
    ERROR,
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ScanResult(
    @JsonNames("filename", "Filename")
    val filename: String,
    @JsonNames("result", "Result")
    val result: Result,
)
