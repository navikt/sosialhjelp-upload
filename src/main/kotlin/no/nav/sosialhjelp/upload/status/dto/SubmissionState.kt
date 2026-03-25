package no.nav.sosialhjelp.upload.status.dto

import kotlinx.serialization.Serializable

@Serializable
data class SubmissionState(
    val submissionId: String,
    val uploads: List<UploadSuccessState>,
    val error: String? = null,
)
