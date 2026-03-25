package no.nav.sosialhjelp.upload.tus

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.Error.Companion.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import no.nav.sosialhjelp.upload.validation.Result
import no.nav.sosialhjelp.upload.validation.ScanResult
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import okhttp3.Headers
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TusMockDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        return when (request.url.encodedPath) {
            "/convert" ->
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .body("pdf-content")
                    .headers(Headers.headersOf("Content-Type", "application/pdf"))
                    .build()

            "/virus" -> {
                val content = request.body?.utf8() ?: ""
                val result =
                    if (content.contains("infected")) {
                        ScanResult("file", Result.FOUND)
                    } else {
                        ScanResult("file", Result.OK)
                    }
                val body = Json.encodeToString(listOf(result))
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .body(body)
                    .headers(Headers.headersOf("Content-Type", "application/json"))
                    .build()
            }

            else -> MockResponse().newBuilder().code(404).build()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TusUploadServiceIntegrationTest {
    private lateinit var dsl: DSLContext
    private lateinit var mockWebServer: MockWebServer
    private lateinit var uploadRepository: UploadRepository
    private lateinit var submissionRepository: SubmissionRepository
    private lateinit var tusUploadService: TusUploadService
    private lateinit var mellomlagringClient: MellomlagringClient
    private lateinit var encryptionService: EncryptionService
    private lateinit var notificationService: SubmissionNotificationService

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        dsl = PostgresTestContainer.dsl
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = TusMockDispatcher()
        mockWebServer.start()

        notificationService = SubmissionNotificationService(PostgresTestContainer.dataSource)
        uploadRepository = UploadRepository(notificationService)
        submissionRepository = SubmissionRepository(dsl)

        val virusScanner = VirusScanner(mockWebServer.url("/virus").toString())
        val validator = UploadValidator(virusScanner)
        val gotenbergService = GotenbergService(mockWebServer.url("/convert").toString())

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
                mellomlagringClient = mellomlagringClient,
                encryptionService = encryptionService,
            )
    }

    @AfterAll
    fun teardown() {
        mockWebServer.close()
    }

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(UPLOAD).execute()
    }

    // region create

    @Test
    fun `create should insert upload record and return UUID`() {
        val externalId = UUID.randomUUID().toString()
        val personident = "12345678910"

        val uploadId = tusUploadService.create(externalId, "test.pdf", 100L, personident)

        val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
        assertNotNull(row)
        assertEquals("test.pdf", row[UPLOAD.ORIGINAL_FILENAME])
        assertEquals(0L, row[UPLOAD.UPLOAD_OFFSET])
    }

    @Test
    fun `create with same externalId as different user should throw UploadForbiddenException`() {
        val externalId = UUID.randomUUID().toString()
        tusUploadService.create(externalId, "first.pdf", 50L, "11111111111")

        assertThrows<TusUploadService.UploadForbiddenException> {
            tusUploadService.create(externalId, "second.pdf", 50L, "99999999999")
        }
    }

    // endregion

    // region getUploadInfo

    @Test
    fun `getUploadInfo returns offset and totalSize`() {
        val externalId = UUID.randomUUID().toString()
        val personident = "12345678910"
        val uploadId = tusUploadService.create(externalId, "info.pdf", 42L, personident)

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
            val uploadId = tusUploadService.create(externalId, "partial.pdf", (content.size * 2).toLong(), personident)

            val newOffset = tusUploadService.appendChunk(uploadId, 0L, content, "token")

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
                mellomlagringClient.uploadFile(any(), any(), any(), any(), any())
            } returns filId

            val uploadId = tusUploadService.create(externalId, "complete.pdf", content.size.toLong(), personident)
            tusUploadService.appendChunk(uploadId, 0L, content, "token")

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNotNull(row)
            assertEquals(filId, row[UPLOAD.FIL_ID])
            assertNull(row[UPLOAD.CHUNK_DATA], "chunk_data should be cleared after mellomlagring upload")
        }

    @Test
    fun `appendChunk stores errors and clears chunk when file is too large`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val oversizedContent = ByteArray(MAX_FILE_SIZE + 1) { 1 }

            val uploadId =
                tusUploadService.create(
                    externalId,
                    "toobig.pdf",
                    (MAX_FILE_SIZE + 1).toLong(),
                    personident,
                )
            tusUploadService.appendChunk(uploadId, 0L, oversizedContent, "token")

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

            val uploadId =
                tusUploadService.create(
                    externalId,
                    "virus.pdf",
                    content.size.toLong(),
                    personident,
                )
            tusUploadService.appendChunk(uploadId, 0L, content, "token")

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
            coEvery {
                mellomlagringClient.uploadFile(
                    navEksternRefId = any(),
                    filename = "document.pdf",
                    contentType = any(),
                    data = any(),
                    token = any(),
                )
            } returns filId

            val uploadId = tusUploadService.create(externalId, "document.docx", content.size.toLong(), personident)
            tusUploadService.appendChunk(uploadId, 0L, content, "token")

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertEquals(filId, row!![UPLOAD.FIL_ID])
        }

    // endregion

    // region delete

    @Test
    fun `delete removes upload from database`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val personident = "12345678910"
            val uploadId = tusUploadService.create(externalId, "delete-me.pdf", 10L, personident)

            tusUploadService.delete(uploadId, "token")

            val row = dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
            assertNull(row)
        }

    // endregion
}
