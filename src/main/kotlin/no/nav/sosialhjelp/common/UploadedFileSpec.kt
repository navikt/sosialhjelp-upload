package no.nav.sosialhjelp.common

import java.io.File

data class UploadedFileSpec(
    val nameWithoutExtension: String,
    val extension: String,
) {
    companion object {
        fun fromFilename(filename: String): UploadedFileSpec =
            File(filename).let { UploadedFileSpec(it.nameWithoutExtension, it.extension) }
    }
}
