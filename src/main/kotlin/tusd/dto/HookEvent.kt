package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HookEvent(
    val upload: FileInfo,
    val httpRequest: HTTPRequest,
)
