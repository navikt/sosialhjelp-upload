package no.nav.sosialhjelp.upload.fs

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.StorageOptions
import io.ktor.server.plugins.di.annotations.Property
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit
import com.google.cloud.storage.Storage as GcpStorage

interface Storage {
    fun store(
        fileName: String,
        content: ByteArray,
        contentType: String,
    )

    fun retrieve(fileName: String): ByteArray?

    fun delete(fileName: String)

    fun createSignedUrl(fileName: String): String?
}

class FileSystemStorage(
    @Property("storage.basePath") private val basePath: String,
) : Storage {
    override fun store(
        fileName: String,
        content: ByteArray,
        contentType: String,
    ) {
        File("$basePath/$fileName").writeBytes(content)
    }

    override fun retrieve(fileName: String): ByteArray? = File("$basePath/$fileName").readBytes()

    override fun delete(fileName: String) {
        File("$basePath/$fileName").delete()
    }

    override fun createSignedUrl(fileName: String): String = "http://localhost:3000/sosialhjelp/innsyn/api/upload-api/thumbnails/$fileName"
}

class GcpBucketStorage(
    @Property("storage.bucketName") private val bucketName: String,
    @Property("storage.gcsCredentials") private val gcsCredentials: String,
) : Storage {
    private val log = LoggerFactory.getLogger(this::class.java)

    val storage: GcpStorage =
        StorageOptions
            .newBuilder()
            .setCredentials(
                GoogleCredentials.fromStream(gcsCredentials.byteInputStream()),
            ).build()
            .service

    override fun store(
        fileName: String,
        content: ByteArray,
        contentType: String,
    ) {
        try {
            storage.create(BlobInfo.newBuilder(BucketInfo.of(bucketName), fileName).setContentType(contentType).build(), content)
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

    override fun createSignedUrl(fileName: String): String? =
        try {
            val blobInfo = BlobInfo.newBuilder(BlobId.of(bucketName, fileName)).build()
            val url = storage.signUrl(blobInfo, 1.days.toLong(DurationUnit.DAYS), TimeUnit.DAYS, GcpStorage.SignUrlOption.withV4Signature())
            url.toString()
        } catch (e: Exception) {
            log.error("Failed to create signed URL for file $fileName in GCP bucket: ${e.message}", e)
            null
        }
}
