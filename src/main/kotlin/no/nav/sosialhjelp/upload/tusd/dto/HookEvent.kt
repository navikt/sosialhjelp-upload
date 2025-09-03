package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HookEvent(
    @SerialName("Upload")
    val upload: FileInfo,
    @SerialName("HTTPRequest")
    val httpRequest: HTTPRequest,
)
