package no.nav.sosialhjelp.progress

import kotlinx.serialization.Serializable

@Serializable
data class DocumentStatusResponse(
    val soknadId: String,
    val vedleggType: String,
    val error: String? = null,
    val uploads: List<UploadStatusResponse>? = null,
)
