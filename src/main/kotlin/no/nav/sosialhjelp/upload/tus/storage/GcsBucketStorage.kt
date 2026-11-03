package no.nav.sosialhjelp.upload.tus.storage

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
    override suspend fun writeChunk(
        key: String,
        data: ByteArray,
    ) {
        withContext(Dispatchers.IO) {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, key)).build()
            storage.create(blobInfo, data)
        }
    }

    override suspend fun composeChunks(
        sourceKeys: List<String>,
        destKey: String,
    ) {
        withContext(Dispatchers.IO) {
            val destBlobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, destKey)).build()
            val composeRequest =
                @Suppress("SpreadOperator")
                Storage.ComposeRequest
                    .newBuilder()
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
            } catch (e: StorageException) {
                if (e.code == 404) {
                    // Object already gone — expected, ignore
                } else {
                    throw e
                }
            }
        }
    }

    override suspend fun exists(key: String): Boolean =
        withContext(Dispatchers.IO) {
            storage.get(BlobId.of(bucketName, key)) != null
        }

    override suspend fun listKeys(prefix: String): List<String> =
        withContext(Dispatchers.IO) {
            storage
                .list(bucketName, Storage.BlobListOption.prefix(prefix))
                .iterateAll()
                .map { it.name }
        }
}
