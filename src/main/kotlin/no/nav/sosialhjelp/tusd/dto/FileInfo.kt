package no.nav.sosialhjelp.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class FileInfo(
    val ID: String,
    val Size: Long,
    val SizeIsDeferred: Boolean,
    val Offset: Long,
    val MetaData: FileMetaData,
    val IsPartial: Boolean,
    val IsFinal: Boolean,
    val PartialUploads: List<String>? = null,
    val Storage: LocalStorage? = null,
)
