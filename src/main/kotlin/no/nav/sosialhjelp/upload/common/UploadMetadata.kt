package no.nav.sosialhjelp.upload.common

import java.util.UUID

@Suppress("ReturnCount")
fun Map<String, String>.toUploadMetadata(): Result<UploadMetadata> {
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
        UploadMetadata(
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

data class UploadMetadata(
    val filename: String,
    val contextId: String,
    val correlationId: UUID?,
    val fiksDigisosId: String?,
    val navEksternRefId: String?,
    val kategori: String?,
    val automaticCleanup: Boolean = false,
)
