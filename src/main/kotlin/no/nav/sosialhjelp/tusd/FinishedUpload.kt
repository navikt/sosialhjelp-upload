package no.nav.sosialhjelp.tusd

import java.io.File

data class FinishedUpload(
    val file: File,
    val originalFilename: String,
)
