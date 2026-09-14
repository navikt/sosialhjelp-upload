package no.nav.sosialhjelp.upload.validation

import org.apache.tika.mime.MimeTypes

val PASSTHROUGH_MIME_TYPES =
    setOf(
        "application/pdf",
        "image/jpeg",
        "image/png",
    )

fun canonicalExtension(
    mimeType: String,
    filenameExtension: String,
): String {
    val tikaMimeType = MimeTypes.getDefaultMimeTypes().forName(mimeType)
    val suppliedExtension = ".${filenameExtension.lowercase()}"
    return when {
        suppliedExtension in tikaMimeType.extensions -> filenameExtension.lowercase()
        tikaMimeType.extension.isNotEmpty() -> tikaMimeType.extension.removePrefix(".")
        else -> filenameExtension.lowercase()
    }
}
