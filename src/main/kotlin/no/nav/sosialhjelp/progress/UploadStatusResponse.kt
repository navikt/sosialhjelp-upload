package no.nav.sosialhjelp.progress

import kotlinx.serialization.Serializable

@Serializable
data class UploadStatusResponse(
    val id: String,
    val error: String? = null,
    val pages: List<PageStatusResponse>? = null,
)
