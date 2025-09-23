package no.nav.sosialhjelp.upload.fs

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.StorageOptions
import io.ktor.server.plugins.di.annotations.Property
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import com.google.cloud.storage.Storage as GcpStorage

interface Storage {
    fun store(
        fileName: String,
        content: ByteArray,
    )

    fun retrieve(fileName: String): ByteArray?

    fun delete(fileName: String)
}

class FileSystemStorage(
    @Property("storage.basePath") private val basePath: String,
) : Storage {
    override fun store(
        fileName: String,
        content: ByteArray,
    ) {
        File("$basePath/$fileName").writeBytes(content)
    }

    override fun retrieve(fileName: String): ByteArray? = File("$basePath/$fileName").readBytes()

    override fun delete(fileName: String) {
        File("$basePath/$fileName").delete()
    }
}

class GcpBucketStorage(
    @Property("storage.bucketName") private val bucketName: String,
) : Storage {
    private val log = LoggerFactory.getLogger(this::class.java)

    val storage: GcpStorage = StorageOptions.getDefaultInstance().service

    override fun store(
        fileName: String,
        content: ByteArray,
    ) {
        try {
            storage.create(BlobInfo.newBuilder(BucketInfo.of(bucketName), fileName).build(), content)
        } catch (e: Exception) {
            log.error("Failed to store file in GCP bucket: ${e.message}", e)
            throw IOException("Failed to store file in GCP bucket: ${e.message}", e)
        }
    }

    override fun retrieve(fileName: String): ByteArray? =
        try {
            storage.get(BlobId.of(bucketName, fileName))?.getContent()
        } catch (e: Exception) {
            log.error("Failed to retrieve file from GCP bucket: ${e.message}", e)
            null
        }

    override fun delete(fileName: String) {
        storage.delete(BlobId.of(bucketName, fileName))
    }
}
