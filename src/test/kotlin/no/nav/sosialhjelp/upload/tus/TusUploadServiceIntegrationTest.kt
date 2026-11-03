package no.nav.sosialhjelp.upload.tus

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.TestUtils.awaitUploadTerminal
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.Error.Companion.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.Submission.Companion.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.pdf.GotenbergConversionResult
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.upload.ChunkAssemblyService
import no.nav.sosialhjelp.upload.upload.FileConversionService
import no.nav.sosialhjelp.upload.upload.MellomlagringStorageService
import no.nav.sosialhjelp.upload.upload.ProcessingWorker
import no.nav.sosialhjelp.upload.upload.UploadProcessingQueries
import no.nav.sosialhjelp.upload.upload.UploadProcessingService
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import no.nav.sosialhjelp.upload.validation.Result
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LargeClass")
class TusUploadServiceIntegrationTest {
    private lateinit var dsl: DSLContext
    private lateinit var tusSubmissionQueries: TusSubmissionQueries
    private lateinit var tusUploadService: TusUploadService
    private lateinit var mellomlagringClient: MellomlagringClient
    private lateinit var encryptionService: EncryptionService
    private lateinit var virusScanner: VirusScanner
    private lateinit var gotenbergService: GotenbergService
    private lateinit var fiksClient: FiksClient
    private lateinit var processingWorker: ProcessingWorker
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val correlationId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        dsl = PostgresTestContainer.dsl

        tusSubmissionQueries = TusSubmissionQueries()
        val tusUploadQueries = TusUploadQueries()
        val uploadProcessingQueries = UploadProcessingQueries()

        virusScanner = mockk()
        gotenbergService = mockk()
        fiksClient = mockk(relaxed = true)
        val validator = UploadValidator(virusScanner, SimpleMeterRegistry())

        mellomlagringClient = mockk()
        encryptionService = mockk()
        coEvery { encryptionService.encryptBytes(any()) } answers { firstArg() }

        val chunkStorage =
            FileSystemStorage(
                java.nio.file.Files
                    .createTempDirectory("sosialhjelp-upload-test")
                    .toFile(),
            )
        val meterRegistry = SimpleMeterRegistry()
        val chunkAssemblyService = ChunkAssemblyService(chunkStorage)
        val fileConversionService = FileConversionService(gotenbergService)
        val mellomlagringStorageService = MellomlagringStorageService(encryptionService, mellomlagringClient)
        val uploadProcessingService =
            UploadProcessingService(
                dsl = dsl,
                uploadProcessingQueries = uploadProcessingQueries,
                chunkAssemblyService = chunkAssemblyService,
                validator = validator,
                fileConversionService = fileConversionService,
                mellomlagringStorageService = mellomlagringStorageService,
                meterRegistry = meterRegistry,
            )

        val processingWorker = ProcessingWorker(dsl, uploadProcessingQueries, uploadProcessingService)
        processingWorker.start(processingScope)
        this.processingWorker = processingWorker

        tusUploadService =
            TusUploadService(
                tusUploadQueries = tusUploadQueries,
                tusSubmissionQueries = tusSubmissionQueries,
                uploadProcessingQueries = uploadProcessingQueries,
                dsl = dsl,
                validator = validator,
                fiksClient = fiksClient,
                mellomlagringClient = mellomlagringClient,
                chunkStorage = chunkStorage,
                chunkAssemblyService = chunkAssemblyService,
                processingWorker = processingWorker,
                meterRegistry = meterRegistry,
            )
    }

    @AfterAll
    fun stopWorkers() {
        // The test DB is shared between test classes; idle workers must not claim their uploads.
        processingScope.cancel()
    }

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(SUBMISSION).execute()
        // Default stub: all files are clean
        coEvery { virusScanner.scan(any()) } returns Result.OK
    }

    // region create

    @Test
    fun `create should insert upload record and return UUID`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            createMockSubmission(dsl, externalId)

            val uploadId =
                tusUploadService.create(
                    TusMetadata("test.pdf", externalId, correlationId, null, "id", null),
                    100L,
                    personident,
                    "test-token",
                )

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNotNull(row)
            assertEquals("test.pdf", row[UPLOAD.ORIGINAL_FILENAME])
            assertEquals(0L, row[UPLOAD.UPLOAD_OFFSET])
        }

    @Test
    fun `create with same externalId as different user should throw UploadForbiddenException`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            createMockSubmission(dsl, externalId, ownerIdent = "11111111111")
            tusUploadService.create(
                TusMetadata("first.pdf", externalId, correlationId, null, "id", null),
                50L,
                "11111111111",
                "test-token",
            )

            assertFailsWith<TusUploadService.UploadForbiddenException> {
                tusUploadService.create(
                    TusMetadata("second.pdf", externalId, correlationId, null, "id", null),
                    50L,
                    "99999999999",
                    "test-token",
                )
            }
        }

    // endregion

    // region getUploadInfo

    @Test
    fun `getUploadInfo returns offset and totalSize`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("info.pdf", externalId, correlationId, null, "id", null),
                    42L,
                    personident,
                    "test-token",
                )

            val (offset, total) = tusUploadService.getUploadInfo(uploadId)

            assertEquals(0L, offset)
            assertEquals(42L, total)
        }

    // endregion

    // region appendChunk + post-processing

    @Test
    fun `appendChunk stores chunk and updates offset`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "hello world".toByteArray()
            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("partial.pdf", externalId, correlationId, null, "id", null),
                    (content.size * 2).toLong(),
                    personident,
                    "test-token",
                )

            val newOffset = tusUploadService.appendChunk(uploadId, 0L, content)

            assertEquals(content.size.toLong(), newOffset)
            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals(content.size.toLong(), row!![UPLOAD.UPLOAD_OFFSET])
        }

    @Test
    fun `appendChunk triggers mellomlagring upload when upload completes`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = minimalPdf()
            val filId = UUID.randomUUID()
            coEvery {
                mellomlagringClient.uploadFile(any(), any(), any(), any())
            } returns filId

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("complete.pdf", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNotNull(row)
            assertEquals(filId, row[UPLOAD.FIL_ID])
            assertNull(row[UPLOAD.GCS_KEY], "gcs_key should be cleared after mellomlagring upload")
            assertEquals(false, row[UPLOAD.CONVERTED])
            assertEquals("application/pdf", row[UPLOAD.CONTENT_TYPE])
        }

    @Test
    fun `oversized upload is rejected at create and refuses chunks`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val oversizedContent = ByteArray(MAX_FILE_SIZE + 1) { 1 }

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("toobig.pdf", externalId, correlationId, null, "id", null),
                    (MAX_FILE_SIZE + 1).toLong(),
                    personident,
                    "test-token",
                )
            assertFailsWith<TusUploadQueries.UploadNotAcceptingChunksException> {
                tusUploadService.appendChunk(uploadId, 0L, oversizedContent)
            }

            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(ERROR.UPLOAD.eq(uploadId))
                    .fetch()
            assertTrue(errors.isNotEmpty, "Errors should be recorded for oversized file")
            assertTrue(errors.any { it[ERROR.CODE]?.contains("FILE_TOO_LARGE") == true })
        }

    @Test
    fun `appendChunk stores errors and clears chunk for infected file`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "infected content".toByteArray()

            coEvery { virusScanner.scan(any()) } returns Result.FOUND

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("virus.pdf", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(ERROR.UPLOAD.eq(uploadId))
                    .fetch()
            assertTrue(errors.isNotEmpty, "Errors should be recorded for infected file")
            assertTrue(errors.any { it[ERROR.CODE]?.contains("POSSIBLY_INFECTED") == true })
        }

    @Test
    fun `appendChunk converts non-pdf to pdf before uploading to mellomlagring`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "word-doc-content".toByteArray()
            val filId = UUID.randomUUID()
            val pdfBytes = "converted-pdf-content".toByteArray()

            coEvery { gotenbergService.convertToPdf(any(), any()) } returns GotenbergConversionResult.Success(pdfBytes)
            coEvery {
                mellomlagringClient.uploadFile(
                    navEksternRefId = any(),
                    filename = match { it.startsWith("document-") && it.endsWith(".pdf") },
                    contentType = any(),
                    data = any(),
                )
            } returns filId

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("document.docx", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals(filId, row!![UPLOAD.FIL_ID])
            assertEquals(true, row[UPLOAD.CONVERTED])
            assertEquals("application/pdf", row[UPLOAD.CONTENT_TYPE])
        }

    @Test
    fun `appendChunk rejects zip files as unsupported filetype`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            // Minimal zip file header (0x504B03040 = "PK\x03\x04")
            val zipContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00)

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("archive.zip", externalId, correlationId, null, "id", null),
                    zipContent.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, zipContent)

            awaitUploadTerminal(dsl, uploadId)

            val errors = dsl.selectFrom(ERROR).where(ERROR.UPLOAD.eq(uploadId)).fetch()
            assertTrue(errors.isNotEmpty, "Zip file should be rejected with validation error")
            assertTrue(
                errors.any { it[ERROR.CODE]?.contains("FILETYPE_NOT_SUPPORTED") == true },
                "Error should be FILETYPE_NOT_SUPPORTED for zip files",
            )

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNull(row!![UPLOAD.FIL_ID], "Upload should not have filId if rejected")
        }

    @Test
    fun `appendChunk marks upload as failed if PDF conversion fails`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "word-doc-content".toByteArray()

            coEvery { gotenbergService.convertToPdf(any(), any()) } throws
                RuntimeException("Gotenberg service error: timeout")

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("document.docx", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals(
                "FAILED",
                row!![UPLOAD.PROCESSING_STATUS],
                "Upload should be marked as FAILED when conversion fails",
            )
            assertNull(row[UPLOAD.FIL_ID], "Upload should not have filId when conversion fails")
        }

    @Test
    fun `appendChunk records FILETYPE_NOT_SUPPORTED validation when Gotenberg returns 400`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "unsupported-content".toByteArray()

            coEvery { gotenbergService.convertToPdf(any(), any()) } returns
                GotenbergConversionResult.UnsupportedFiletype("xyz")

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("document.xyz", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals("FAILED", row!![UPLOAD.PROCESSING_STATUS])
            assertNull(row[UPLOAD.FIL_ID])

            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(ERROR.UPLOAD.eq(uploadId))
                    .fetch()
            assertTrue(errors.isNotEmpty, "Validation error should be recorded when Gotenberg returns 400")
            assertTrue(
                errors.any { it[ERROR.CODE]?.contains("FILETYPE_NOT_SUPPORTED") == true },
                "Error code should be FILETYPE_NOT_SUPPORTED",
            )
        }

    // endregion

    /** Creates a minimal valid PDF so Tika detects the pass-through MIME type from its bytes. */
    private fun minimalPdf(): ByteArray {
        PDDocument().use { document ->
            document.addPage(PDPage())
            return ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
    }

    // region delete

    @Test
    fun `delete removes upload from database`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("delete-me.pdf", externalId, correlationId, null, "", null),
                    10L,
                    personident,
                    "test-token",
                )

            tusUploadService.delete(uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNull(row)
        }

    @Test
    fun `delete does not remove upload from database when mellomlagring delete fails`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = minimalPdf()
            val filId = UUID.randomUUID()
            coEvery {
                mellomlagringClient.uploadFile(any(), any(), any(), any())
            } returns filId
            coEvery {
                mellomlagringClient.deleteFile(any(), eq(filId), any())
            } throws RuntimeException("mellomlagring is down")

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("delete-fails.pdf", externalId, correlationId, null, "id", null),
                    content.size.toLong(),
                    personident,
                    "test-token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)
            awaitUploadTerminal(dsl, uploadId)

            assertFailsWith<TusUploadService.MellomlagringDeleteException> {
                tusUploadService.delete(uploadId)
            }

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNotNull(row, "Upload should not be deleted from database when mellomlagring delete fails")
        }

    @Test
    fun `concurrent uploads on different contextIds with same fiksDigisosId get different navEksternRefIds`() =
        runTest {
            val fiksDigisosId = UUID.randomUUID().toString()
            val contextId1 = UUID.randomUUID().toString()
            val contextId2 = UUID.randomUUID().toString()
            val personident = "12345678910"

            // Both calls return the same remote Fiks state — simulating the real race where
            // neither submission has been submitted to Fiks yet, so ettersendtInfoNAV.ettersendelser
            // has not advanced. The fix must derive different IDs using the local DB counter.
            coEvery { fiksClient.getNewNavEksternRefId(fiksDigisosId, any(), null) } returns "base-ref-0001"
            coEvery { fiksClient.getNewNavEksternRefId(fiksDigisosId, any(), "base-ref-0001") } returns "base-ref-0002"

            tusUploadService.create(
                TusMetadata("file1.pdf", contextId1, correlationId, fiksDigisosId, null, null),
                100L,
                personident,
                "token",
            )
            tusUploadService.create(
                TusMetadata("file2.pdf", contextId2, correlationId, fiksDigisosId, null, null),
                100L,
                personident,
                "token",
            )

            val ref1 = tusSubmissionQueries.getNavEksternRefIdByContextId(dsl.configuration(), contextId1)
            val ref2 = tusSubmissionQueries.getNavEksternRefIdByContextId(dsl.configuration(), contextId2)

            assertNotNull(ref1)
            assertNotNull(ref2)
            assertTrue(
                ref1 != ref2,
                "Expected different navEksternRefIds for different submissions, but both got: $ref1",
            )
        }

    @Test
    fun `klage upload with client UUID ref does not affect søknad counter`() =
        runTest {
            val fiksDigisosId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val klageRef = "3f2a1b4c-0000-4000-8000-000000009999"

            tusUploadService.create(
                TusMetadata(
                    filename = "klage.pdf",
                    contextId = UUID.randomUUID().toString(),
                    correlationId = correlationId,
                    fiksDigisosId = fiksDigisosId,
                    navEksternRefId = klageRef,
                    kategori = null,
                ),
                100L,
                personident,
                "token",
            )

            assertEquals(
                null,
                tusSubmissionQueries.getMaxNavEksternRefIdForFiksDigisosId(dsl.configuration(), fiksDigisosId),
            )

            coEvery { fiksClient.getNewNavEksternRefId(fiksDigisosId, any(), null) } returns "$fiksDigisosId-0001"
            val soknadContext = UUID.randomUUID().toString()
            tusUploadService.create(
                TusMetadata("soknad.pdf", soknadContext, correlationId, fiksDigisosId, null, null),
                100L,
                personident,
                "token",
            )
            assertEquals(
                "$fiksDigisosId-0001",
                tusSubmissionQueries.getNavEksternRefIdByContextId(dsl.configuration(), soknadContext),
            )
        }

    // endregion

    // region races

    /** Starts an upload whose mellomlagring call blocks until [release] completes. */
    private suspend fun startBlockedUpload(
        filId: UUID,
        release: CompletableDeferred<Unit>,
    ): UUID {
        val entered = CompletableDeferred<Unit>()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            release.await()
            filId
        }
        coEvery { mellomlagringClient.deleteFile(any(), any(), any()) } returns Unit
        val content = minimalPdf()
        val contextId = UUID.randomUUID().toString()
        createMockSubmission(dsl, contextId)
        val uploadId =
            tusUploadService.create(
                TusMetadata("race.pdf", contextId, correlationId, null, "id", null),
                content.size.toLong(),
                "12345678910",
                "token",
            )
        tusUploadService.appendChunk(uploadId, 0L, content)
        withContext(Dispatchers.Default) { withTimeout(10_000) { entered.await() } }
        return uploadId
    }

    private suspend fun settle() = withContext(Dispatchers.Default) { delay(1_500) }

    @Test
    fun `delete waits for a concurrent storage commit and deletes the committed file`() =
        runBlocking {
            val filId = UUID.randomUUID()
            val release = CompletableDeferred<Unit>()
            val uploadId = startBlockedUpload(filId, release)

            val deletion = async(Dispatchers.IO) { tusUploadService.delete(uploadId) }
            delay(200)
            assertTrue(!deletion.isCompleted, "Delete must wait for the storage transaction")
            release.complete(Unit)
            withTimeout(10_000) { deletion.await() }

            coVerify(timeout = 3_000) { mellomlagringClient.deleteFile(any(), eq(filId), any()) }
            assertNull(dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne())
        }

    @Test
    fun `late processing result does not overwrite an upload that recovery marked FAILED`() =
        runBlocking {
            clearMocks(mellomlagringClient)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            coEvery { virusScanner.scan(any()) } coAnswers {
                entered.complete(Unit)
                release.await()
                Result.OK
            }
            val content = minimalPdf()
            val contextId = UUID.randomUUID().toString()
            createMockSubmission(dsl, contextId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("late.pdf", contextId, correlationId, null, "id", null),
                    content.size.toLong(),
                    "12345678910",
                    "token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)
            withTimeout(10_000) { entered.await() }

            dsl
                .update(UPLOAD)
                .set(UPLOAD.PROCESSING_STATUS, "FAILED")
                .where(UPLOAD.ID.eq(uploadId))
                .execute()
            release.complete(Unit)
            settle()

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals("FAILED", row!![UPLOAD.PROCESSING_STATUS])
            assertNull(row[UPLOAD.FIL_ID])
        }

    @Test
    fun `expired attempt cannot replace the file committed by a newer attempt`() =
        runBlocking {
            clearMocks(mellomlagringClient)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val scans = AtomicInteger()
            val winningFileId = UUID.randomUUID()
            coEvery { virusScanner.scan(any()) } coAnswers {
                if (scans.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
                Result.OK
            }
            coEvery { mellomlagringClient.replaceFile(any(), any(), any(), any()) } returns winningFileId
            val content = minimalPdf()
            val contextId = UUID.randomUUID().toString()
            createMockSubmission(dsl, contextId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("race.pdf", contextId, correlationId, null, "id", null),
                    content.size.toLong(),
                    "12345678910",
                    "token",
                )
            tusUploadService.appendChunk(uploadId, 0L, content)
            withTimeout(10_000) { entered.await() }
            dsl
                .update(UPLOAD)
                .set(UPLOAD.PROCESSING_STARTED_AT, OffsetDateTime.now().minusMinutes(10))
                .where(UPLOAD.ID.eq(uploadId))
                .execute()
            processingWorker.wake()
            withTimeout(10_000) {
                while (dsl
                        .select(UPLOAD.PROCESSING_STATUS)
                        .from(UPLOAD)
                        .where(UPLOAD.ID.eq(uploadId))
                        .fetchOne()
                        ?.value1() != "COMPLETE"
                ) {
                    delay(50)
                }
            }
            release.complete(Unit)
            settle()
            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()!!
            assertEquals(winningFileId, row[UPLOAD.FIL_ID])
            coVerify(exactly = 1) { mellomlagringClient.replaceFile(any(), any(), any(), any()) }
            coVerify(exactly = 0) { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
        }

    @Test
    fun `appendChunk is rejected for an upload that is no longer PENDING`() =
        runTest {
            val contextId = UUID.randomUUID().toString()
            createMockSubmission(dsl, contextId)
            val uploadId =
                tusUploadService.create(
                    TusMetadata("late.pdf", contextId, correlationId, null, "id", null),
                    20L,
                    "12345678910",
                    "token",
                )
            dsl
                .update(UPLOAD)
                .set(UPLOAD.PROCESSING_STATUS, "FAILED")
                .where(UPLOAD.ID.eq(uploadId))
                .execute()

            assertFailsWith<TusUploadQueries.UploadNotAcceptingChunksException> {
                tusUploadService.appendChunk(uploadId, 0L, ByteArray(10))
            }
        }

    // endregion
}
