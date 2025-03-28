package no.nav.sosialhjelp.status.dto

import kotlinx.serialization.Serializable

@Serializable
data class PageState(
    val pageNumber: Int,
    val thumbnail: String? = null,
)
