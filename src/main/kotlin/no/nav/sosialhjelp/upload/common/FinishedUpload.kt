package no.nav.sosialhjelp.upload.common

import java.io.File

// TODO: Probably something that can be merged with UploadedFileSpec
data class FinishedUpload(
    val file: ByteArray,
    val originalFileExtension: String,
)
