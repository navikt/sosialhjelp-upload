@file:Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")

package no.nav.sosialhjelp.upload.validation

import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.UploadWithFilename
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import java.io.File
import java.text.Normalizer
import java.util.UUID

// 10 MB
const val MAX_FILE_SIZE = 10 * 1024 * 1024

enum class ValidationCode {
    FILE_TOO_LARGE,
    INVALID_FILENAME,
    VIRUS,
}

interface Validation {
    val message: String
    val code: ValidationCode
}

class FileSizeValidation() : Validation {
    override val message: String = "File size exceeds the maximum allowed limit of $MAX_FILE_SIZE bytes."
    override val code: ValidationCode = ValidationCode.FILE_TOO_LARGE
}

class FilenameValidation() : Validation {
    override val message: String = "Filename contains illegal characters."
    override val code: ValidationCode = ValidationCode.INVALID_FILENAME
}

class VirusValidation() : Validation {
    override val message: String = "File may contain virus."
    override val code: ValidationCode = ValidationCode.VIRUS
}

class UploadValidator(val virusScanner: VirusScanner, val filePathFactory: FilePathFactory) {
    suspend fun validate(upload: UploadWithFilename, request: HookRequest) {
        val file = File(filePathFactory.getOriginalUploadPath(UUID.fromString(request.event.upload.id)).toString())
        val validations = listOf(validateFileSize(request.event.upload.size), validateFilename(Filename(request.event.upload.metadata.filename)), runVirusScan(file.readBytes()))
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
