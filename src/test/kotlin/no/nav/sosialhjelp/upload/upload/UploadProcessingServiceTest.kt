package no.nav.sosialhjelp.upload.upload

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLockTimeoutException
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import org.jooq.TransactionalRunnable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class UploadProcessingServiceTest {
    private val dsl = mockk<DSLContext>()
    private val tx = mockk<Configuration>()
    private val queries = mockk<UploadProcessingQueries>(relaxed = true)
    private val chunkAssemblyService = mockk<ChunkAssemblyService>(relaxed = true)
    private val filePipeline = mockk<FilePipeline>()
    private val lock = mockk<NavEksternRefIdLock>()

    private val service =
        UploadProcessingService(
            dsl = dsl,
            uploadProcessingQueries = queries,
            chunkAssemblyService = chunkAssemblyService,
            filePipeline = filePipeline,
            lock = lock,
        )

    private val uploadId = UUID.randomUUID()
    private val submissionId = UUID.randomUUID()
    private val gcsKey = "uploads/$uploadId"
    private val composedKey = "$gcsKey-${UUID.randomUUID()}"

    @BeforeEach
    fun setup() {
        mockkObject(UploadNotifications)
        every { UploadNotifications.notifyChange(tx, uploadId) } just runs
        every { dsl.transactionResult(any<TransactionalCallable<Any?>>()) } answers {
            firstArg<TransactionalCallable<Any?>>().run(tx)
        }
        every { dsl.transaction(any<TransactionalRunnable>()) } answers {
            firstArg<TransactionalRunnable>().run(tx)
        }
        every { queries.getUploadForProcessing(tx, uploadId) } returns
            UploadForProcessing(
                filename = "test.pdf",
                gcsKey = gcsKey,
                submissionId = submissionId,
                navEksternRefId = "nav-ref-1",
                fiksDigisosId = null,
            )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(UploadNotifications)
    }

    @Test
    fun `lock timeout marks upload failed and cleans up composed GCS object`() =
        runBlocking {
            coEvery { chunkAssemblyService.assembleChunks(uploadId, gcsKey) } returns
                ("data".toByteArray() to composedKey)
            coEvery { filePipeline.prepare(uploadId, "test.pdf", any()) } returns readyResult()
            coEvery { lock.shared<Boolean>("nav-ref-1", any()) } throws
                NavEksternRefIdLockTimeoutException(NavEksternRefIdLock.Mode.SHARED, RuntimeException("timeout"))

            service.process(uploadId)

            verify(exactly = 1) { queries.markFailed(tx, uploadId) }
            verify(exactly = 1) { UploadNotifications.notifyChange(tx, uploadId) }
            coVerify(exactly = 1) { chunkAssemblyService.deleteGcsObjects(uploadId, composedKey) }
        }

    @Test
    fun `unhandled exception in prepare marks upload failed and cleans up composed GCS object`() =
        runBlocking {
            coEvery { chunkAssemblyService.assembleChunks(uploadId, gcsKey) } returns
                ("data".toByteArray() to composedKey)
            coEvery { filePipeline.prepare(uploadId, "test.pdf", any()) } throws IllegalStateException("boom")

            service.process(uploadId)

            verify(exactly = 1) { queries.markFailed(tx, uploadId) }
            verify(exactly = 1) { UploadNotifications.notifyChange(tx, uploadId) }
            coVerify(exactly = 1) { chunkAssemblyService.deleteGcsObjects(uploadId, composedKey) }
        }

    @Test
    fun `cancellation exception is rethrown without marking upload failed`() =
        runBlocking {
            coEvery { chunkAssemblyService.assembleChunks(uploadId, gcsKey) } returns
                ("data".toByteArray() to composedKey)
            coEvery { filePipeline.prepare(uploadId, "test.pdf", any()) } returns readyResult()
            coEvery { lock.shared<Boolean>("nav-ref-1", any()) } throws CancellationException("shutdown")

            assertFailsWith<CancellationException> {
                service.process(uploadId)
            }

            verify(exactly = 0) { queries.markFailed(any(), any()) }
            coVerify(exactly = 0) { chunkAssemblyService.deleteGcsObjects(any(), any()) }
        }

    private fun readyResult() =
        PrepareResult.Ready(
            filename = "test.pdf",
            data = "data".toByteArray(),
            contentType = "application/pdf",
            converted = false,
            sha512 = "abc",
            fileExtension = "pdf",
            startTime = 0L,
        )
}
