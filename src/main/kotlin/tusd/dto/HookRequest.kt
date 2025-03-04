package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HookRequest(
    val type: HookType,
    val event: HookEvent,
)
