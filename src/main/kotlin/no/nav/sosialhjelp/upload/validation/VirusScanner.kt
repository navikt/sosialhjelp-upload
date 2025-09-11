package no.nav.sosialhjelp.upload.validation

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.io.ByteArrayInputStream

class VirusScanner(@Property("virus.scanner.url") val url: String) {
    val httpClient = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun scan(file: ByteArray): Result {
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
