package no.nav.sosialhjelp.tusd

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*

class PdfConversionService {
    val gotenbergClient =
        HttpClient(CIO) {
            expectSuccess = false
            defaultRequest {
                url("http://localhost:3010/forms/libreoffice/convert")
            }
        }

    suspend fun convertToPdf(upload: FinishedUpload): HttpResponse {
        val file = upload.file.readBytes()
        val headers = Headers.Companion.build { append(HttpHeaders.ContentDisposition, """filename="${upload.originalFilename}"""") }
        return gotenbergClient.submitFormWithBinaryData(formData { append("file", file, headers) })
    }
}
