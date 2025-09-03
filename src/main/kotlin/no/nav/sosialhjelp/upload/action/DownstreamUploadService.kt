package no.nav.sosialhjelp.upload.action

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.forms.*
import io.ktor.http.*

class DownstreamUploadService {
    val client =
        HttpClient(CIO) {
            expectSuccess = false
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
