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

@Suppress("ReturnCount")
fun Map<String, String>.toTusMetadata(): Result<TusMetadata> {
    val filename = this["filename"] ?: return Result.failure(IllegalArgumentException("Mangler filename"))
    val contextId = this["contextId"] ?: return Result.failure(IllegalArgumentException("Mangler contextId"))
    val correlationId =
        this["correlationId"]?.let {
            runCatching { UUID.fromString(it) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Ugyldig correlationId. Må være uuid"))
        }
    val fiksDigisosId = this["fiksDigisosId"]
    val navEksternRefId = this["navEksternRefId"]
    val kategori = this["kategori"]
    // Default false: en manglende eller ugyldig verdi skal aldri kunne føre til at
    // vedlegg slettes automatisk. Se V1.15__add_automatic_cleanup.sql.
    val automaticCleanup = this["automaticCleanup"]?.equals("true", ignoreCase = true) ?: false

    return Result.success(
        TusMetadata(
            filename = filename,
            contextId = contextId,
            correlationId = correlationId,
            fiksDigisosId = fiksDigisosId,
            navEksternRefId = navEksternRefId,
            kategori = kategori,
            automaticCleanup = automaticCleanup,
        ),
    )
}

data class TusMetadata(
    val filename: String,
    val contextId: String,
    val correlationId: UUID?,
    val fiksDigisosId: String?,
    val navEksternRefId: String?,
    val kategori: String?,
    val automaticCleanup: Boolean = false,
)
