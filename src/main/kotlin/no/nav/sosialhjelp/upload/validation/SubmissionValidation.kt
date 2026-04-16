package no.nav.sosialhjelp.upload.validation

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.database.Status
import no.nav.sosialhjelp.upload.database.Upload

const val MAX_FILES_PER_SUBMISSION = 30
const val MAX_TOTAL_SIZE_BYTES = 150L * 1024 * 1024

@Serializable
enum class SubmissionValidationCode {
    TOO_MANY_FILES,
    TOTAL_SIZE_TOO_LARGE,
}

@Serializable
data class SubmissionValidationErrorResponse(
    val errors: List<SubmissionValidationCode>,
)

class SubmissionValidationException(val violations: List<SubmissionValidationCode>) :
    Exception("Submission validation failed: ${violations.joinToString()}")

fun validateSubmissionUploads(uploads: List<Upload>): List<SubmissionValidationCode> {
    val completeUploads = uploads.filter { it.status == Status.COMPLETE }
    val violations = mutableListOf<SubmissionValidationCode>()
    if (completeUploads.size > MAX_FILES_PER_SUBMISSION) {
        violations += SubmissionValidationCode.TOO_MANY_FILES
    }
    val totalSize = completeUploads.sumOf { it.mellomlagringStorrelse ?: 0L }
    if (totalSize > MAX_TOTAL_SIZE_BYTES) {
        violations += SubmissionValidationCode.TOTAL_SIZE_TOO_LARGE
    }
    return violations
}
