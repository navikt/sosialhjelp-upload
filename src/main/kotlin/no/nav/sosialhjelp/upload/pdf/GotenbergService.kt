package no.nav.sosialhjelp.upload.pdf

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.plugins.di.annotations.Property
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import no.nav.sosialhjelp.upload.common.FinishedUpload
import org.slf4j.LoggerFactory
import java.io.File

class GotenbergService(
    @Property("gotenberg.url") gotenbergUrl: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private val gotenbergClient =
        HttpClient(CIO) {
            expectSuccess = false
            defaultRequest { url(gotenbergUrl) }
        }

    private fun buildHeaders(originalFiletype: String): Headers =
        Headers.build { append(HttpHeaders.ContentDisposition, """filename="file.$originalFiletype"""") }

    suspend fun convertToPdf(upload: FinishedUpload): ByteReadChannel {
        val res =
            try {
                gotenbergClient
                    .submitFormWithBinaryData(
                        formData { append("file", ChannelProvider { upload.file }, buildHeaders(upload.originalFileExtension)) },
                    )
            } catch (e: Exception) {
                logger
                throw RuntimeException("Failed to convert file type ${upload.originalFileExtension} to PDF")
            }

        check(res.status.isSuccess()) { "Failed to convert file type ${upload.originalFileExtension} to PDF: ${res.status}" }

        return res.bodyAsChannel()
    }

    suspend fun merge(pdfs: Flow<File>): ByteArray {
        val byteReadChannels = pdfs.map { it.readBytes() }.toList()
        val res =
            gotenbergClient.submitFormWithBinaryData(
                formData {
                    byteReadChannels.forEachIndexed { index, pdf -> append("file", pdf, headers = buildHeaders("$index.pdf")) }
                    append("merge", "true")
                    append("pdfa", "PDF/A-3b")
                    append("pdfua", "true")
                },
            )

        check(res.status.isSuccess()) { "Failed to merge ${res.status}" }

        return res.readRawBytes()
    }
}
