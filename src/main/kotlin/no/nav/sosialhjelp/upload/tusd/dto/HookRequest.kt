package no.nav.sosialhjelp.upload.tusd.dto

import HookType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class HookRequest(
    @SerialName("Type")
    val type: HookType,
    @SerialName("Event")
    val event: HookEvent,
)
