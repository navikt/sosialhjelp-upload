package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileMetadata(
    val filename: String,
    val externalId: String
)
