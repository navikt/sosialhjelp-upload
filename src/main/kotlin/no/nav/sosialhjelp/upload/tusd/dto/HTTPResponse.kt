package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HTTPResponse(
    val status: Int = 200,
    val header: Map<String, List<String>> = emptyMap(),
    val body: String? = null,
)
