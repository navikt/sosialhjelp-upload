package no.nav.sosialhjelp.upload.validation

import kotlinx.serialization.Serializable

@Serializable
enum class ValidationCode {
    FILE_TOO_LARGE,
    INVALID_FILENAME,
    POSSIBLY_INFECTED,
    FILETYPE_NOT_SUPPORTED,
    ENCRYPTED_PDF,
    INVALID_PDF,
}

interface Validation {
    val message: String
    val code: ValidationCode
}

class FileSizeValidation : Validation {
    override val message: String = "File size exceeds the maximum allowed limit of $MAX_FILE_SIZE bytes."
    override val code: ValidationCode = ValidationCode.FILE_TOO_LARGE
}

class FilenameValidation : Validation {
    override val message: String = "Filename contains illegal characters."
    override val code: ValidationCode = ValidationCode.INVALID_FILENAME
}

class VirusValidation : Validation {
    override val message: String = "File may contain virus."
    override val code: ValidationCode = ValidationCode.POSSIBLY_INFECTED
}

class FileTypeValidation : Validation {
    override val message: String = "File type is not supported."
    override val code: ValidationCode = ValidationCode.FILETYPE_NOT_SUPPORTED
}

class EncryptedPdfValidation : Validation {
    override val message: String = "PDF is encrypted."
    override val code: ValidationCode = ValidationCode.ENCRYPTED_PDF
}

class InvalidPdfValidation : Validation {
    override val message: String = "Could not load PDF document."
    override val code: ValidationCode = ValidationCode.INVALID_PDF
}
