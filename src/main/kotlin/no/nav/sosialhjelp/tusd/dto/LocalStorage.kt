package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class LocalStorage(
    val InfoPath: String,
    val Path: String,
    val Type: String,
)
