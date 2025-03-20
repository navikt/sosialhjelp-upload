package no.nav.sosialhjelp.tusd

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import java.io.File

class GotenbergService(
    environment: ApplicationEnvironment,
) {
    val gotenbergClient =
        HttpClient(CIO) {
            expectSuccess = false
            defaultRequest {
                url(environment.config.property("gotenberg.url").getString())
            }
        }

    private fun buildHeaders(originalFiletype: String): Headers =
        Headers.Companion.build { append(HttpHeaders.ContentDisposition, """filename="file.$originalFiletype"""") }

    suspend fun convertToPdf(upload: FinishedUpload): HttpResponse =
        gotenbergClient.submitFormWithBinaryData(
            formData { append("file", upload.file.readBytes(), buildHeaders(upload.originalFileExtension)) },
        )

    suspend fun merge(pdfs: List<File>): HttpResponse =
        gotenbergClient.submitFormWithBinaryData(
            formData {
                pdfs.forEachIndexed { index, pdf -> append("file", pdf.readBytes(), buildHeaders("$index.pdf")) }
                append("merge", "true")
                append("pdfa", "PDF/A-3b")
                append("pdfua", "true")
            },
        )
}
