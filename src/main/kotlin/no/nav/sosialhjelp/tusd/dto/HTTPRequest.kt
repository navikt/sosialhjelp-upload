package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HTTPRequest(
    val Method: String,
    val URI: String,
    val RemoteAddr: String,
    val Header: Map<String, List<String>>,
)
