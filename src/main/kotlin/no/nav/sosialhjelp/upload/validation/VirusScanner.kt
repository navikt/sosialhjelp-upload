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

class VirusScanner(@Property("virus.scanner.url") val url: String) {
    val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun scan(file: ByteArray): Result {
        if (url.isEmpty()) {
            // No virus scanner configured, assume all files are clean
            return Result.OK
        }
        return httpClient.put(url) {
            setBody(ByteReadChannel(file))
        }.body<ScanResult>().result
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
