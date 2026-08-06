package no.nav.sosialhjelp.upload.tus

import java.util.Base64
import java.util.UUID

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
                    val value =
                        runCatching { String(Base64.getDecoder().decode(parts[1].trim())) }.getOrNull()
                            ?: return@mapNotNull null
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

fun Map<String, String>.toTusMetadata(): Pair<TusMetadata?, String?> {
    val filename = this["filename"] ?: return null to "Mangler filename"
    val contextId = this["contextId"] ?: return null to "Mangler contextId"
    val correlationId = this["correlationId"]?.let {
        runCatching { UUID.fromString(it) }.getOrNull() ?: return null to "Ugyldig correlationId. Må være uuid"
    }
    val fiksDigisosId = this["fiksDigisosId"]
    val navEksternRefId = this["navEksternRefId"]
    val kategori = this["kategori"]

    return TusMetadata(
        filename = filename,
        contextId = contextId,
        correlationId = correlationId,
        fiksDigisosId = fiksDigisosId,
        navEksternRefId = navEksternRefId,
        kategori = kategori
    ) to null
}

data class TusMetadata(
    val filename: String,
    val contextId: String,
    val correlationId: UUID?,
    val fiksDigisosId: String?,
    val navEksternRefId: String?,
    val kategori: String?,
)
