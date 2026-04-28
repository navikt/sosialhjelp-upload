package no.nav.sosialhjelp.upload.status.dto

import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.validation.ValidationCode

@Serializable
data class SubmissionState(
    val submissionId: String,
    val uploads: List<UploadDto>,
    val error: String? = null,
    val validations: List<ValidationCode>,
    val status: Status,
) {
    enum class Status {
        DELETED, ACTIVE
    }
}
