package no.nav.sosialhjelp.common

import java.io.File

// TODO: Probably something that can be merged with UploadedFileSpec
data class FinishedUpload(
    val file: File,
    val originalFileExtension: String,
)
