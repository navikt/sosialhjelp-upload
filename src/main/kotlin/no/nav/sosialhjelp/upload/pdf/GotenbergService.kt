package no.nav.sosialhjelp.upload.pdf

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.annotations.Property
import org.slf4j.LoggerFactory

class GotenbergService(
    @Property("gotenberg.url") gotenbergUrl: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val gotenbergClient =
        HttpClient(CIO) {
            expectSuccess = false
            defaultRequest { url(gotenbergUrl) }
        }

    private fun buildHeaders(filetype: String): Headers =
        Headers.build { append(HttpHeaders.ContentDisposition, """filename="file.$filetype"""") }

    suspend fun convertToPdf(data: ByteArray, extension: String): ByteArray {
        val res =
            gotenbergClient
                .submitFormWithBinaryData(
                    formData { append("file", data, buildHeaders(extension)) },
                )

        check(res.status.isSuccess()) { "Failed to convert file type $extension to PDF: ${res.status}" }

        return res.readRawBytes()
    }
}
