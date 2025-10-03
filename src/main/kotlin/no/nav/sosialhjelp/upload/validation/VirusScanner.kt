package no.nav.sosialhjelp.upload.validation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import org.slf4j.LoggerFactory

class VirusScanner(
    @Property("virus.scanner.url") val url: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    val httpClient =
        HttpClient(CIO) {
            expectSuccess = true
            install(ContentNegotiation) {
                json()
            }
        }

    suspend fun scan(file: ByteReadChannel): Result {
        try {
            if (url.isEmpty()) {
                // No virus scanner configured, assume all files are clean
                return Result.OK
            }
            val body =
                httpClient
                    .put(url) {
                        setBody(file)
                    }.body<List<ScanResult>>()
            if (body.all { it.result == Result.OK }) {
                return Result.OK
            }
            return Result.ERROR
        } catch (e: Exception) {
            // If the virus scanner is unreachable or returns an error, we assume the file is clean
            // This is to avoid blocking uploads due to temporary issues with the virus scanner
            logger.warn("Virus scanner error: ${e.message}, assuming file is clean")
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
private data class ScanResult(
    @JsonNames("filename", "Filename")
    val filename: String,
    @JsonNames("result", "Result")
    val result: Result,
)
