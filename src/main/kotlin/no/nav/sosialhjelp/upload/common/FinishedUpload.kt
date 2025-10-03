package no.nav.sosialhjelp.upload.common

import io.ktor.utils.io.ByteReadChannel

// TODO: Probably something that can be merged with UploadedFileSpec
data class FinishedUpload(
    val file: ByteReadChannel,
    val originalFileExtension: String,
)
