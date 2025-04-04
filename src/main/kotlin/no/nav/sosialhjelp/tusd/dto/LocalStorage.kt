package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocalStorage(
    @SerialName("InfoPath")
    val infoPath: String,
    @SerialName("Path")
    val path: String,
    @SerialName("Type")
    val type: String,
)
