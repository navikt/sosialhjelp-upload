package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.pdf.GotenbergConversionResult
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.validation.PASSTHROUGH_MIME_TYPES
import no.nav.sosialhjelp.upload.validation.canonicalExtension
import java.io.File

/**
 * Converts uploaded files to PDF when needed.
 * PDF, JPEG and PNG files are passed through unchanged.
 * All other formats are sent to Gotenberg for conversion.
 */
class FileConversionService(
    private val gotenbergService: GotenbergService,
) {
    sealed class ConversionResult {
        data class Success(
            val filename: String,
            val data: ByteArray,
            val contentType: String,
            val converted: Boolean,
        ) : ConversionResult()

        data class UnsupportedFiletype(
            val extension: String,
        ) : ConversionResult()
    }

    /**
     * Routes by detected MIME type, never the client-supplied filename extension.
     */
    suspend fun convertIfNeeded(
        filename: String,
        mimeType: String,
        data: ByteArray,
    ): ConversionResult {
        val file = File(filename)
        val extension = canonicalExtension(mimeType, file.extension)
        val canonicalFilename =
            if (extension.isEmpty()) file.name else "${file.nameWithoutExtension}.$extension"
        if (mimeType in PASSTHROUGH_MIME_TYPES) {
            return ConversionResult.Success(canonicalFilename, data, mimeType, converted = false)
        }
        return when (val converted = gotenbergService.convertToPdf(data, extension)) {
            is GotenbergConversionResult.Success ->
                ConversionResult.Success(
                    "${file.nameWithoutExtension}.pdf",
                    converted.bytes,
                    "application/pdf",
                    converted = true,
                )
            is GotenbergConversionResult.UnsupportedFiletype -> ConversionResult.UnsupportedFiletype(extension)
        }
    }
}
