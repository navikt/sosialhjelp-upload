package no.nav.sosialhjelp.upload.validation

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.Upload

const val MAX_FILES_PER_SUBMISSION = 30
const val MAX_TOTAL_SIZE_BYTES = 150L * 1024 * 1024

@Serializable
enum class SubmissionValidationCode {
    NO_FILES,
    TOO_MANY_FILES,
    TOTAL_SIZE_TOO_LARGE,
    KLAGE_TRUKKET,
    KLAGE_ETTERSENDELSE_FRIST_UTLOPT,
    UPLOADS_IN_PROGRESS,
    FILES_MISSING,
}

@Serializable
data class MissingFileDto(
    val uploadId: String,
    val originalFilename: String?,
)

@Serializable
data class SubmissionConflictResponse(
    val errors: List<SubmissionValidationCode>,
    val missing: List<MissingFileDto> = emptyList(),
)

/** Rows promised files that no longer exist in mellomlagring. The rows have been deleted. */
class SubmissionFilesMissingException(
    val missing: List<MissingFileDto>,
) : Exception("Files missing in mellomlagring: ${missing.size}")

/** TUS uploads still in flight; their files may be landing in mellomlagring right now. */
class SubmissionUploadsInProgressException : Exception("Uploads in progress")

@Serializable
data class SubmissionValidationErrorResponse(
    val errors: List<SubmissionValidationCode>,
)

class SubmissionValidationException(
    val violations: List<SubmissionValidationCode>,
) : Exception("Submission validation failed: ${violations.joinToString()}")

fun validateSubmissionUploads(uploads: List<Upload>): List<SubmissionValidationCode> {
    val completeUploads = uploads.filter { it.status == Status.COMPLETE }
    val violations = mutableListOf<SubmissionValidationCode>()
    if (completeUploads.isEmpty()) {
        violations += SubmissionValidationCode.NO_FILES
    }
    if (completeUploads.size > MAX_FILES_PER_SUBMISSION) {
        violations += SubmissionValidationCode.TOO_MANY_FILES
    }
    val totalSize = completeUploads.sumOf { it.mellomlagringStorrelse ?: 0L }
    if (totalSize > MAX_TOTAL_SIZE_BYTES) {
        violations += SubmissionValidationCode.TOTAL_SIZE_TOO_LARGE
    }
    return violations
}
