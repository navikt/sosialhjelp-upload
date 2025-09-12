@file:Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")

package no.nav.sosialhjelp.upload.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.UploadWithFilename
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import org.apache.tika.Tika
import java.io.File
import java.text.Normalizer
import java.util.UUID

// 10 MB
const val MAX_FILE_SIZE = 10 * 1024 * 1024

val SUPPORTED_MIME_TYPES = setOf(
    "text/plain",
    "application/pdf",
    "image/png",
    "image/jpeg",
    "image/tiff",
    "image/bmp",
    "image/gif",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.oasis.opendocument.text",
)

class UploadValidator(val virusScanner: VirusScanner, val filePathFactory: FilePathFactory) {
    suspend fun validate(request: HookRequest): List<Validation> {
        // TODO: Ikke ByteArray (og bucket)
        val file = File(filePathFactory.getOriginalUploadPath(UUID.fromString(request.event.upload.id)).toString())

        val validations = coroutineScope {
            val virusScanValidation = async(Dispatchers.IO) { runVirusScan(file.readBytes()) }

            listOf(
                validateFileSize(request.event.upload.size),
                validateFilename(Filename(request.event.upload.metadata.filename)),
                validateFileType(file),
                virusScanValidation.await(),
            )
        }
        return validations.filterNotNull()
    }

    private fun validateFileType(file: File): Validation? {
        val mimeType = Tika().detect(file)
        if (mimeType !in SUPPORTED_MIME_TYPES) {
            return FileTypeValidation()
        }
        return null;
    }

    private suspend fun runVirusScan(file: ByteArray): Validation? = when (virusScanner.scan(file)) {
        Result.FOUND -> VirusValidation()
        Result.ERROR -> TODO()
        Result.OK -> null
    }

    private fun validateFileSize(filesize: Long): Validation? {
        if (filesize > MAX_FILE_SIZE) {
            return FileSizeValidation()
        }
        return null
    }

    private fun validateFilename(filename: Filename): Validation? {
        if (filename.containsIllegalCharacters()) {
            return FilenameValidation()
        }
        return null
    }

}


@JvmInline
private value class Filename(
    val value: String,
) {
    fun sanitize() = Normalizer.normalize(value, Normalizer.Form.NFC).trim()

    fun containsIllegalCharacters(): Boolean = this.sanitize().contains("[^a-zæøåA-ZÆØÅ0-9 (),._–-]".toRegex())
}
