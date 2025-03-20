package no.nav.sosialhjelp.progress.dto

import kotlinx.serialization.Serializable

@Serializable
data class UploadSuccessState(
    val originalFilename: String,
    val pages: List<PageState>? = null,
)
