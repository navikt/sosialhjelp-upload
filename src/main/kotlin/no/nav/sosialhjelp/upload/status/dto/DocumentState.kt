package no.nav.sosialhjelp.upload.status.dto

import kotlinx.serialization.Serializable

@Serializable
data class DocumentState(
    val documentId: String,
    val uploads: List<UploadSuccessState>,
    val error: String? = null,
)
