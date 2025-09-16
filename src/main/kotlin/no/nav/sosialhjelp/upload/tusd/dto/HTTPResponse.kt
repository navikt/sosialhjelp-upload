package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HTTPResponse(
    val StatusCode: Int = 200,
    val header: Map<String, String> = emptyMap(),
    val body: String? = null,
)
