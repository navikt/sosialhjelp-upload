package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileInfo(
    val id: String,
    val size: Long,
    val sizeIsDeferred: Boolean,
    val offset: Long,
    val metaData: Map<String, String>,
    val isPartial: Boolean,
    val isFinal: Boolean,
    val partialUploads: List<String> = emptyList(),
    val storage: Map<String, String>? = null,
)
