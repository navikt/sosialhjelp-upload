package no.nav.sosialhjelp.upload.storage

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GcsBucketStorage(
    private val storage: Storage,
    private val bucketName: String,
) : ChunkStorage {
    override suspend fun writeChunk(key: String, data: ByteArray) {
        withContext(Dispatchers.IO) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, key)).build()
            storage.create(blobInfo, data)
        }
    }

    override suspend fun composeChunks(sourceKeys: List<String>, destKey: String) {
        withContext(Dispatchers.IO) {
            val destBlobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, destKey)).build()
            val composeRequest = Storage.ComposeRequest.newBuilder()
                .addSource(*sourceKeys.toTypedArray())
                .setTarget(destBlobInfo)
                .build()
            storage.compose(composeRequest)
        }
    }

    override suspend fun readObject(key: String): ByteArray =
        withContext(Dispatchers.IO) {
            storage.readAllBytes(BlobId.of(bucketName, key))
        }

    override suspend fun deleteObject(key: String) {
        withContext(Dispatchers.IO) {
            try {
                storage.delete(BlobId.of(bucketName, key))
            } catch (_: StorageException) {
                // Ignore — object may already be gone
            }
        }
    }

    override suspend fun exists(key: String): Boolean =
        withContext(Dispatchers.IO) {
            storage.get(BlobId.of(bucketName, key)) != null
        }

    override suspend fun listKeys(prefix: String): List<String> =
        withContext(Dispatchers.IO) {
            storage.list(bucketName, Storage.BlobListOption.prefix(prefix))
                .iterateAll()
                .map { it.name }
        }
}
