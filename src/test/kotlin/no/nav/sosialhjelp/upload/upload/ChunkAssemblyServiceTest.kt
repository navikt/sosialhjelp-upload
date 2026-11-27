package no.nav.sosialhjelp.upload.upload

import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkAssemblyServiceTest {
    @Test
    fun `deleteGcsObjects removes chunks and random-suffixed composed object without composedKey`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val storage = FileSystemStorage(tempDir)
        val service = ChunkAssemblyService(storage)
        val uploadId = UUID.randomUUID()
        val otherUploadId = UUID.randomUUID()

        val chunkKey = "uploads/$uploadId-chunk-0"
        val composedKey = "uploads/$uploadId-${UUID.randomUUID()}"
        val otherKey = "uploads/$otherUploadId-${UUID.randomUUID()}"

        storage.writeChunk(chunkKey, "chunk".toByteArray())
        storage.writeChunk(composedKey, "composed".toByteArray())
        storage.writeChunk(otherKey, "other".toByteArray())

        service.deleteGcsObjects(uploadId)

        assertFalse(storage.exists(chunkKey))
        assertFalse(storage.exists(composedKey))
        assertTrue(storage.exists(otherKey))
    }
}
