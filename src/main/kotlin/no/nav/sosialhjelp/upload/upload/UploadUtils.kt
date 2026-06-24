package no.nav.sosialhjelp.upload.upload

import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun getSha512(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-512")
    md.update(data)
    val digest = md.digest()
    return buildString(digest.size * 2) {
        digest.forEach { append("%02x".format(it)) }
    }
}

/**
 * Generates a unique mellomlagring filename by appending the first segment of [uploadId]
 * (8 hex characters) before the extension. The base name is truncated to 50 characters
 * to keep total filenames manageable.
 *
 * Examples:
 *   "document.pdf"  + UUID b1f7f6d1-... → "document-b1f7f6d1.pdf"
 *   "a".repeat(60) + ".pdf" → "a".repeat(50) + "-b1f7f6d1.pdf"
 */
internal fun makeUniqueMellomlagringFilename(
    filename: String,
    uploadId: UUID,
): String {
    val file = File(filename)
    val extension = file.extension
    val baseName = file.nameWithoutExtension.take(50)
    val uuidSuffix = uploadId.toString().substringBefore('-')
    return if (extension.isNotEmpty()) "$baseName-$uuidSuffix.$extension" else "$baseName-$uuidSuffix"
}
