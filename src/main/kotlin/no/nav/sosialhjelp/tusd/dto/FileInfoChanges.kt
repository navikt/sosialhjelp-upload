package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileInfoChanges(
    val id: String? = null,
    val metaData: Map<String, String>? = null,
    val storage: Map<String, String>? = null,
)
