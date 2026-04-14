package no.nav.sosialhjelp.upload.storage

/**
 * Abstraction over chunk storage for in-progress TUS uploads.
 *
 * Chunks are written as individual objects keyed by `uploads/{uploadId}-chunk-{offset}`.
 * On the final chunk, all chunk objects are composed into `uploads/{uploadId}` and
 * the individual chunk objects are deleted.
 */
interface ChunkStorage {
    /** Write a chunk. Key format: `uploads/{uploadId}-chunk-{offset}`. */
    suspend fun writeChunk(key: String, data: ByteArray)

    /**
     * Compose multiple source objects into a single destination object.
     * Sources must be provided in order. After composition the caller is responsible
     * for deleting the source objects.
     */
    suspend fun composeChunks(sourceKeys: List<String>, destKey: String)

    /** Read all bytes from an object. Used after compose to retrieve the assembled file. */
    suspend fun readObject(key: String): ByteArray

    /** Delete an object. Does not throw if the object doesn't exist. */
    suspend fun deleteObject(key: String)

    /** Returns true if the object exists. */
    suspend fun exists(key: String): Boolean

    /** List all object keys with the given prefix. */
    suspend fun listKeys(prefix: String): List<String>
}
