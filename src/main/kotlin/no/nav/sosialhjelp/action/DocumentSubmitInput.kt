package no.nav.sosialhjelp.action

import io.ktor.http.*
import kotlinx.serialization.Serializable

@Serializable
data class DocumentSubmitInput(
    val targetUrl: Url,
)
