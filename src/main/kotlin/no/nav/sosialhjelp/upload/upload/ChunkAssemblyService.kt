package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.tus.storage.ChunkStorage
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Assembles a completed TUS upload from its individual GCS chunk objects
 * into a single contiguous byte array, and handles GCS cleanup.
 */
class ChunkAssemblyService(
    private val chunkStorage: ChunkStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Lists all chunk objects for [uploadId], sorts them by offset, composes them into
     * a single GCS object, reads the assembled bytes, and returns the data together with
     * the composed object key (needed for later cleanup).
     */
    suspend fun assembleChunks(
        uploadId: UUID,
        gcsKey: String,
    ): Pair<ByteArray, String> =
        withContext(ioDispatcher) {
            val chunkPrefix = "uploads/$uploadId-chunk-"
            val chunkKeys =
                chunkStorage.listKeys(chunkPrefix).sortedBy { key ->
                    key.removePrefix(chunkPrefix).toLongOrNull() ?: 0L
                }
            if (chunkKeys.isEmpty()) {
                error("No chunk objects found for upload $uploadId at prefix $chunkPrefix")
            }
            // Use a unique key per attempt so we never try to overwrite an existing GCS object,
            // which would be rejected with 403 if a previous composed object is still present.
            val composedKey = "$gcsKey-${UUID.randomUUID()}"
            chunkStorage.composeChunks(chunkKeys, composedKey)
            chunkStorage.readObject(composedKey) to composedKey
        }

    /**
     * Deletes all chunk objects for [uploadId] and the composed object (if any).
     * Errors are logged but not rethrown so cleanup never blocks the caller.
     */
    suspend fun deleteGcsObjects(
        uploadId: UUID,
        composedKey: String? = null,
    ) {
        val chunkPrefix = "uploads/$uploadId-chunk-"
        val keysToDelete = listOf(composedKey ?: "uploads/$uploadId")
        runCatching {
            val chunkKeys = chunkStorage.listKeys(chunkPrefix)
            (chunkKeys + keysToDelete).distinct().forEach { key ->
                runCatching { chunkStorage.deleteObject(key) }
                    .onFailure { logger.warn("Failed to delete GCS object $key", it) }
            }
        }.onFailure { logger.warn("Failed to list GCS chunks for cleanup of upload $uploadId", it) }
    }
}
