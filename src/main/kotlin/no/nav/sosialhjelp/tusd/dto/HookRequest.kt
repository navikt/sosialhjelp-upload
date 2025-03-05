package no.nav.sosialhjelp.tusd.dto

import HookType
import kotlinx.serialization.Serializable

@Serializable
data class HookRequest(
    val Type: HookType,
    val Event: HookEvent,
)
