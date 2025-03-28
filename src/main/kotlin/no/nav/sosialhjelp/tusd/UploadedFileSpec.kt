package no.nav.sosialhjelp.tusd

import java.io.File

data class UploadedFileSpec(
    val nameWithoutExtension: String,
    val extension: String,
) {
    companion object {
        fun fromFilename(filename: String): UploadedFileSpec {
            val file = File(filename)
            return UploadedFileSpec(file.nameWithoutExtension, file.extension)
        }
    }
}
