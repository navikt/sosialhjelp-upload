package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.pdf.GotenbergService
import java.io.File

/**
 * Converts uploaded files to PDF when needed.
 * PDF, JPEG, JPG and PNG files are passed through unchanged.
 * All other formats are sent to Gotenberg for conversion.
 */
class FileConversionService(
    private val gotenbergService: GotenbergService,
) {
    /**
     * Returns the file unchanged if it is already a supported direct-store format.
     * Otherwise converts to PDF via Gotenberg and returns the new filename + converted bytes.
     */
    suspend fun convertIfNeeded(
        filename: String,
        data: ByteArray,
    ): Pair<String, ByteArray> {
        val extension = File(filename).extension.lowercase()
        if (extension in listOf("pdf", "jpeg", "jpg", "png")) {
            return filename to data
        }
        val pdfName = File(filename).nameWithoutExtension + ".pdf"
        val converted = gotenbergService.convertToPdf(data, extension)
        return pdfName to converted
    }
}
