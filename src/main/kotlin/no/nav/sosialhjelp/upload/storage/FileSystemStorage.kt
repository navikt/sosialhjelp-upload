package no.nav.sosialhjelp.upload.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Filesystem-backed chunk storage for local development.
 * Chunks are written to `{baseDir}/{key}`. No GCS credentials required.
 */
class FileSystemStorage(
    baseDir: File = File(System.getProperty("java.io.tmpdir"), "sosialhjelp-upload"),
) : ChunkStorage {
    private val root = baseDir.also { it.mkdirs() }

    private fun file(key: String) = File(root, key).also { it.parentFile.mkdirs() }

    override suspend fun writeChunk(key: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            file(key).writeBytes(data)
        }
    }

    override suspend fun composeChunks(sourceKeys: List<String>, destKey: String) {
        withContext(Dispatchers.IO) {
            val dest = file(destKey)
            dest.outputStream().use { out ->
                sourceKeys.forEach { key -> file(key).inputStream().use { it.copyTo(out) } }
            }
        }
    }

    override suspend fun readObject(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            file(key).readBytes()
        }

    override suspend fun deleteObject(key: String) {
        withContext(Dispatchers.IO) {
            file(key).delete()
        }
    }

    override suspend fun exists(key: String): Boolean =
        withContext(Dispatchers.IO) {
            file(key).exists()
        }

    override suspend fun listKeys(prefix: String): List<String> =
        withContext(Dispatchers.IO) {
            val prefixFile = File(root, prefix)
            val dir = prefixFile.parentFile ?: root
            val namePrefix = prefixFile.name
            dir.listFiles { f -> f.name.startsWith(namePrefix) }
                ?.map { root.toURI().relativize(it.toURI()).path }
                ?: emptyList()
        }
}
