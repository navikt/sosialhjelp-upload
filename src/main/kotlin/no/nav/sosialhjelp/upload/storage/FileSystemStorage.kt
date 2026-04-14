package no.nav.sosialhjelp.upload.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Filesystem-backed chunk storage for local development.
 * Chunks are written to `{baseDir}/{key}`.
 */
class FileSystemStorage(
    baseDir: File = File(System.getProperty("java.io.tmpdir"), "sosialhjelp-upload"),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChunkStorage {
    private val root = baseDir.also { it.mkdirs() }

    private fun file(key: String) = File(root, key).also { it.parentFile.mkdirs() }

    override suspend fun writeChunk(key: String, data: ByteArray) {
        withContext(ioDispatcher) {
            file(key).writeBytes(data)
        }
    }

    override suspend fun composeChunks(sourceKeys: List<String>, destKey: String) {
        withContext(ioDispatcher) {
            val dest = file(destKey)
            dest.outputStream().use { out ->
                sourceKeys.forEach { key -> file(key).inputStream().use { it.copyTo(out) } }
            }
        }
    }

    override suspend fun readObject(key: String): ByteArray =
        withContext(ioDispatcher) {
            file(key).readBytes()
        }

    override suspend fun deleteObject(key: String) {
        withContext(ioDispatcher) {
            file(key).delete()
        }
    }

    override suspend fun exists(key: String): Boolean =
        withContext(ioDispatcher) {
            file(key).exists()
        }

    override suspend fun listKeys(prefix: String): List<String> =
        withContext(ioDispatcher) {
            val prefixFile = File(root, prefix)
            val dir = prefixFile.parentFile ?: root
            val namePrefix = prefixFile.name
            dir.listFiles { f -> f.name.startsWith(namePrefix) }
                ?.map { root.toURI().relativize(it.toURI()).path }
                ?: emptyList()
        }
}
