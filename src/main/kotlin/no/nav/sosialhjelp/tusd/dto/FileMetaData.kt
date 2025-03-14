package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileMetaData(
    val filename: String,
    val soknadId: String,
    val vedleggType: String,
)
