package no.nav.sosialhjelp.action

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*

class DownstreamUploadService {
    val client =
        HttpClient(CIO) {
            expectSuccess = false
        }

    suspend fun upload(
        url: Url,
        bytes: ByteArray,
        filename: String,
    ) = client.request {
        url(url)
        method = HttpMethod.Companion.Post
        formData {
            append(
                "file",
                bytes,
                Headers.Companion.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                    append(HttpHeaders.ContentDisposition, "attachment; filename=$filename")
                },
            )
        }
    }
}
