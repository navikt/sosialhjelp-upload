package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HTTPRequest(
    @SerialName("Method")
    val method: String,
    @SerialName("URI")
    val uri: String,
    @SerialName("RemoteAddr")
    val remoteAddr: String,
    @SerialName("Header")
    val header: Map<String, List<String>>,
)
