package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("Type")
sealed class Storage

@Serializable
@SerialName("gcsstore")
class BucketStorage(
    @SerialName("Bucket")
    val bucket: String,
    @SerialName("Key")
    val key: String,
) : Storage()

@Serializable
@SerialName("filestore")
class LocalStorage(
    @SerialName("InfoPath")
    val infoPath: String,
    @SerialName("Path")
    val path: String,
) : Storage()
