package no.nav.sosialhjelp.upload.tus

import java.util.Base64

/**
 * Parses the TUS Upload-Metadata header.
 * Format: "key base64value, key base64value"
 */
internal fun parseMetadata(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    return header
        .split(",")
        .mapNotNull { pair ->
            val parts = pair.trim().split(" ", limit = 2)
            when (parts.size) {
                2 -> {
                    val key = parts[0].trim()
                    val value = runCatching { String(Base64.getDecoder().decode(parts[1].trim())) }.getOrNull() ?: return@mapNotNull null
                    key to value
                }

                1 -> {
                    parts[0].trim() to ""
                }

                else -> {
                    null
                }
            }
        }.toMap()
}
