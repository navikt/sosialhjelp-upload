package no.nav.sosialhjelp.upload.tusd.dto

import kotlinx.serialization.Serializable

@Serializable
data class HookResponse(
    val httpResponse: HTTPResponse = HTTPResponse(),
    val rejectUpload: Boolean = false,
    val rejectTermination: Boolean = false,
    val changeFileInfo: FileInfoChanges? = null,
    val stopUpload: Boolean = false,
)
