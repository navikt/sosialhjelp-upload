package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UploadRecoveryServiceTest {
    @Test
    fun `recoverAll deletes chunks and random-suffixed composed object for stuck uploads`(
        @TempDir tempDir: File,
    ) = runBlocking {
        val storage = FileSystemStorage(tempDir)
        val dsl = mockk<DSLContext>()
        val tx = mockk<Configuration>()
        every { dsl.transactionResult(any<TransactionalCallable<Any?>>()) } answers {
            firstArg<TransactionalCallable<Any?>>().run(tx)
        }
        val queries = mockk<UploadRecoveryQueries>()
        val notificationService = mockk<SubmissionNotificationService>(relaxed = true)

        val uploadId = UUID.randomUUID()
        val otherUploadId = UUID.randomUUID()
        val submissionId = UUID.randomUUID()
        val gcsKey = "uploads/$uploadId"
        val chunkKey = "$gcsKey-chunk-0"
        val composedKey = "$gcsKey-${UUID.randomUUID()}"
        val otherKey = "uploads/$otherUploadId-${UUID.randomUUID()}"

        storage.writeChunk(chunkKey, "chunk".toByteArray())
        storage.writeChunk(composedKey, "composed".toByteArray())
        storage.writeChunk(otherKey, "other".toByteArray())

        every { queries.markStaleProcessingAsFailed(tx, any()) } returns
            listOf(UploadRecoveryQueries.StaleUploadInfo(submissionId, gcsKey))
        every { queries.markHaltedPendingAsFailed(tx, any()) } returns emptyList()

        val service =
            UploadRecoveryService(
                dsl = dsl,
                uploadRecoveryQueries = queries,
                notificationService = notificationService,
                chunkStorage = storage,
                meterRegistry = SimpleMeterRegistry(),
            )

        service.recoverAll()

        coVerify(exactly = 1) { notificationService.notifyUpdate(submissionId) }
        assertFalse(storage.exists(chunkKey))
        assertFalse(storage.exists(composedKey))
        assertTrue(storage.exists(otherKey))
    }
}
