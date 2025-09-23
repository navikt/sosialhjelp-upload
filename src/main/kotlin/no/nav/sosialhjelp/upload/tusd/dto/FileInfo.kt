package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FileInfo(
    @SerialName("ID")
    val id: String,
    @SerialName("Size")
    val size: Long,
    @SerialName("SizeIsDeferred")
    val sizeIsDeferred: Boolean,
    @SerialName("Offset")
    val offset: Long,
    @SerialName("MetaData")
    val metadata: FileMetadata,
    @SerialName("IsPartial")
    val isPartial: Boolean,
    @SerialName("IsFinal")
    val isFinal: Boolean,
    @SerialName("PartialUploads")
    val partialUploads: List<String>? = null,
    @SerialName("Storage")
    val storage: Storage? = null,
)
