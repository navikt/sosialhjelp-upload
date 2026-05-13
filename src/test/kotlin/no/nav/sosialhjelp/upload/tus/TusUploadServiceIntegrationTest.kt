package no.nav.sosialhjelp.upload.tus

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.TestUtils.awaitUploadTerminal
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.Error.Companion.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.Submission.Companion.SUBMISSION
import no.nav.sosialhjelp.upload.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.validation.Result
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TusUploadServiceIntegrationTest {
    private lateinit var dsl: DSLContext
    private lateinit var uploadRepository: UploadRepository
    private lateinit var submissionRepository: SubmissionRepository
    private lateinit var tusUploadService: TusUploadService
    private lateinit var mellomlagringClient: MellomlagringClient
    private lateinit var encryptionService: EncryptionService
    private lateinit var notificationService: SubmissionNotificationService
    private lateinit var virusScanner: VirusScanner
    private lateinit var gotenbergService: GotenbergService
    private lateinit var fiksClient: FiksClient
    private val processingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        dsl = PostgresTestContainer.dsl

        notificationService = SubmissionNotificationService(PostgresTestContainer.dataSource)
        uploadRepository = UploadRepository()
        submissionRepository = SubmissionRepository(dsl)

        virusScanner = mockk()
        gotenbergService = mockk()
        fiksClient = mockk(relaxed = true)
        val validator = UploadValidator(virusScanner)

        mellomlagringClient = mockk()
        encryptionService = mockk()
        coEvery { encryptionService.encryptBytes(any()) } answers { firstArg() }

        tusUploadService =
            TusUploadService(
                uploadRepository = uploadRepository,
                submissionRepository = submissionRepository,
                dsl = dsl,
                validator = validator,
                gotenbergService = gotenbergService,
                fiksClient = fiksClient,
                mellomlagringClient = mellomlagringClient,
                encryptionService = encryptionService,
                chunkStorage = FileSystemStorage(),
                processingScope = processingScope,
                meterRegistry = SimpleMeterRegistry(),
            )
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

            val uploadId = tusUploadService.create(externalId, "test.pdf", 100L, personident, "test-token", null, "id")

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
            tusUploadService.create(externalId, "first.pdf", 50L, "11111111111", "test-token", null, "id")

            assertFailsWith<TusUploadService.UploadForbiddenException> {
                tusUploadService.create(externalId, "second.pdf", 50L, "99999999999", "test-token", null, "id")
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
            val uploadId = tusUploadService.create(externalId, "info.pdf", 42L, personident, "test-token", null, "id")

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
            val uploadId = tusUploadService.create(externalId, "partial.pdf", (content.size * 2).toLong(), personident, "test-token", null, "id")

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
            val content = "hello mellomlagring".toByteArray()
            val filId = UUID.randomUUID()
            coEvery {
                mellomlagringClient.uploadFile(any(), any(), any(), any())
            } returns filId

            createMockSubmission(dsl, externalId)
            val uploadId = tusUploadService.create(externalId, "complete.pdf", content.size.toLong(), personident, "test-token", null, "id")
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNotNull(row)
            assertEquals(filId, row[UPLOAD.FIL_ID])
            assertNull(row[UPLOAD.GCS_KEY], "gcs_key should be cleared after mellomlagring upload")
        }

    @Test
    fun `appendChunk stores errors and clears chunk when file is too large`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val oversizedContent = ByteArray(MAX_FILE_SIZE + 1) { 1 }

            createMockSubmission(dsl, externalId)
            val uploadId =
                tusUploadService.create(
                    externalId,
                    "toobig.pdf",
                    (MAX_FILE_SIZE + 1).toLong(),
                    personident,
                    "test-token",
                    null, "id"
                )
            tusUploadService.appendChunk(uploadId, 0L, oversizedContent)

            awaitUploadTerminal(dsl, uploadId)

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
                    externalId,
                    "virus.pdf",
                    content.size.toLong(),
                    personident,
                    "test-token", null, "id"
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

            coEvery { gotenbergService.convertToPdf(any(), any()) } returns pdfBytes
            coEvery {
                mellomlagringClient.uploadFile(
                    navEksternRefId = any(),
                    filename = match { it.startsWith("document-") && it.endsWith(".pdf") },
                    contentType = any(),
                    data = any(),
                )
            } returns filId

            createMockSubmission(dsl, externalId)
            val uploadId = tusUploadService.create(externalId, "document.docx", content.size.toLong(), personident, "test-token", null, "id")
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals(filId, row!![UPLOAD.FIL_ID])
        }

    @Test
    fun `appendChunk rejects zip files as unsupported filetype`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            // Minimal zip file header (0x504B03040 = "PK\x03\x04")
            val zipContent = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00)

            createMockSubmission(dsl, externalId)
            val uploadId = tusUploadService.create(externalId, "archive.zip", zipContent.size.toLong(), personident, "test-token", null, "id")
            tusUploadService.appendChunk(uploadId, 0L, zipContent)

            awaitUploadTerminal(dsl, uploadId)

            val errors = dsl.selectFrom(ERROR).where(ERROR.UPLOAD.eq(uploadId)).fetch()
            assertTrue(errors.isNotEmpty, "Zip file should be rejected with validation error")
            assertTrue(errors.any { it[ERROR.CODE]?.contains("FILETYPE_NOT_SUPPORTED") == true },
                "Error should be FILETYPE_NOT_SUPPORTED for zip files")

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNull(row!![UPLOAD.FIL_ID], "Upload should not have filId if rejected")
        }

    @Test
    fun `appendChunk marks upload as failed if PDF conversion fails`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val content = "word-doc-content".toByteArray()

            coEvery { gotenbergService.convertToPdf(any(), any()) } throws RuntimeException("Gotenberg service error: timeout")

            createMockSubmission(dsl, externalId)
            val uploadId = tusUploadService.create(externalId, "document.docx", content.size.toLong(), personident, "test-token", null, "id")
            tusUploadService.appendChunk(uploadId, 0L, content)

            awaitUploadTerminal(dsl, uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals("FAILED", row!![UPLOAD.PROCESSING_STATUS], "Upload should be marked as FAILED when conversion fails")
            assertNull(row[UPLOAD.FIL_ID], "Upload should not have filId when conversion fails")
        }

    // endregion

    // region delete

    @Test
    fun `delete removes upload from database`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            createMockSubmission(dsl, externalId)
            val uploadId = tusUploadService.create(externalId, "delete-me.pdf", 10L, personident, "test-token", null, "")

            tusUploadService.delete(uploadId)

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNull(row)
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

            tusUploadService.create(contextId1, "file1.pdf", 100L, personident, "token", fiksDigisosId, null)
            tusUploadService.create(contextId2, "file2.pdf", 100L, personident, "token", fiksDigisosId, null)

            val ref1 = submissionRepository.getNavEksternRefIdByContextId(dsl.configuration(), contextId1)
            val ref2 = submissionRepository.getNavEksternRefIdByContextId(dsl.configuration(), contextId2)

            assertNotNull(ref1)
            assertNotNull(ref2)
            assertTrue(ref1 != ref2, "Expected different navEksternRefIds for different submissions, but both got: $ref1")
        }

    // endregion
}
