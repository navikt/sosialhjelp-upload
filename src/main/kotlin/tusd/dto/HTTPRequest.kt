package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HTTPRequest(
    val method: String,
    val uri: String,
    val remoteAddr: String,
    val header: Map<String, List<String>>,
)
