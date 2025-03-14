package no.nav.sosialhjelp.progress

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(
    val type: String,
    val url: String,
)
