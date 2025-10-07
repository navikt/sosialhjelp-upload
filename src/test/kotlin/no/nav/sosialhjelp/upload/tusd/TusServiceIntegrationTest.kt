package no.nav.sosialhjelp.upload.tusd

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.references.ERROR
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.fs.FileSystemStorage
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.testutils.createFileInfo
import no.nav.sosialhjelp.upload.testutils.createFileMetadata
import no.nav.sosialhjelp.upload.testutils.createHTTPRequest
import no.nav.sosialhjelp.upload.testutils.createHookEvent
import no.nav.sosialhjelp.upload.testutils.createHookRequest
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import no.nav.sosialhjelp.upload.validation.Result
import no.nav.sosialhjelp.upload.validation.ScanResult
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import okhttp3.Headers
import org.jooq.DSLContext
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertNotNull
import org.junit.jupiter.api.assertNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockDispatcher : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
        println("Received request: ${request.url}. Path: ${request.url.encodedPath}")
        return when (request.url.encodedPath) {
            "/convert" -> {
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .body("pdf-content")
                    .headers(Headers.headersOf("Content-Type", "application/pdf", "Content-Length", "11"))
                    .setHeader("Content-Length", "11")
                    .build()
            }

            "/virus" -> {
                val buffer = request.body
                val content = buffer?.utf8() ?: ""
                val response =
                    if (content.contains("infected")) {
                        ScanResult("file", Result.FOUND)
                    } else {
                        ScanResult("file", Result.OK)
                    }.let { Json.encodeToString(listOf(it)) }
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .body(response)
                    .headers(Headers.headersOf("Content-Type", "application/json", "Content-Length", response.length.toString()))
                    .build()
            }

            else -> MockResponse().newBuilder().code(404).build()
        }
    }
}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TusServiceIntegrationTest {
    private lateinit var dsl: DSLContext
    private lateinit var mockWebServer: MockWebServer
    private lateinit var storage: FileSystemStorage
    private lateinit var uploadRepository: UploadRepository
    private lateinit var documentRepository: DocumentRepository
    private lateinit var validator: UploadValidator
    private lateinit var gotenbergService: GotenbergService
    private lateinit var notificationService: DocumentNotificationService
    private lateinit var tempDir: Path
    private lateinit var virusScanner: VirusScanner
    private lateinit var tusService: TusService

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        dsl = PostgresTestContainer.dsl
        mockWebServer = MockWebServer()
        mockWebServer.dispatcher = MockDispatcher()
        mockWebServer.start()
        tempDir = Files.createTempDirectory("upload-test-storage")
        storage = FileSystemStorage(tempDir.toString().also { println(it) })
        notificationService = DocumentNotificationService(PostgresTestContainer.dataSource)
        uploadRepository = UploadRepository(notificationService)
        documentRepository = DocumentRepository(dsl)
        virusScanner = VirusScanner(mockWebServer.url("/virus").toString())
        validator =
            UploadValidator(
                virusScanner,
                storage,
            )
        gotenbergService = GotenbergService(mockWebServer.url("/convert").toString())
        tusService = TusService(uploadRepository, gotenbergService, documentRepository, dsl, validator, storage)
    }

    @AfterAll
    fun teardown() {
        mockWebServer.close()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `preCreate should create upload and return HookResponse with uploadId`() {
        val externalId = UUID.randomUUID().toString()
        val filename = "testfile.pdf"
        val personident = UUID.randomUUID().toString().take(11)
        val tx = dsl.configuration()

        // Insert document into DB
        val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)

        val hookRequest =
            createHookRequest(
                type = HookType.PreCreate,
                fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId)),
            )

        val response = tusService.preCreate(hookRequest, personident)

        // Assert: upload exists in DB
        val uploadId = UUID.fromString(response.changeFileInfo?.id)
        val upload =
            dsl
                .selectFrom(UPLOAD)
                .where(
                    UPLOAD.ID
                        .eq(uploadId),
                ).fetchOne()
        assertNotNull(upload)
        assertEquals(upload.get(UPLOAD.ORIGINAL_FILENAME), filename)
        assertEquals(upload.get(UPLOAD.DOCUMENT_ID), documentId)
    }

    @Test
    fun `postFinish should convert non-pdf file to pdf and update upload`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "testfile.docx"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            // Store a test file in the file system
            val fileContent = "file-content".toByteArray()
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, fileContent)

            // Simulate Gotenberg PDF conversion response
            val pdfContent = "pdf-content".toByteArray()

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId), id = uploadId.toString()),
                )

            // Act
            tusService.postFinish(hookRequest)

            // Assert: PDF file should be stored
            val pdfPath = tempDir.resolve("testfile.pdf")
            assertTrue(Files.exists(pdfPath))
            assertContentEquals(Files.readAllBytes(pdfPath), pdfContent)
            // Assert: upload is updated in DB
            val upload =
                dsl
                    .selectFrom(UPLOAD)
                    .where(
                        UPLOAD.ID
                            .eq(uploadId),
                    ).fetchOne()
            assertNotNull(upload)
            assertEquals(upload.get(UPLOAD.CONVERTED_FILENAME), "testfile.pdf")
        }

    @Test
    fun `preTerminate should reject termination if not owned by user`() {
        val externalId = UUID.randomUUID().toString()
        val filename = "testfile.pdf"
        val personident = UUID.randomUUID().toString().take(11)
        val tx = dsl.configuration()

        // Insert document and upload with a different owner
        val documentId = documentRepository.getOrCreateDocument(tx, externalId, "other-user")
        val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

        val hookRequest =
            createHookRequest(
                HookType.PreTerminate,
                fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId), id = uploadId.toString()),
            )

        val response = tusService.preTerminate(hookRequest, personident)

        // Assert: should reject termination
        assertEquals(response.httpResponse.StatusCode, 403)
        assertTrue(response.rejectTermination)
    }

    @Test
    fun `postTerminate should reject if not owned by user`() {
        val externalId = UUID.randomUUID().toString()
        val filename = "testfile.pdf"
        val personident = UUID.randomUUID().toString().take(11)
        val tx = dsl.configuration()

        // Insert document and upload with a different owner
        val documentId = documentRepository.getOrCreateDocument(tx, externalId, "other-user")
        val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

        val hookRequest =
            createHookRequest(
                HookType.PostFinish,
                fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId), id = uploadId.toString()),
                event =
                    createHookEvent(
                        fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId), id = uploadId.toString()),
                        httpRequest = createHTTPRequest(header = mapOf("Authorization" to listOf("12345678"))),
                    ),
            )

        val response = tusService.postTerminate(hookRequest, personident)

        // Assert: should reject termination
        assertEquals(403, response.httpResponse.StatusCode)
        assertTrue(response.rejectTermination)
    }

    @Test
    fun `postTerminate should delete and return 204 if owned by user`() {
        val externalId = UUID.randomUUID().toString()
        val filename = "testfile.pdf"
        val personident = UUID.randomUUID().toString().take(11)
        val tx = dsl.configuration()

        // Insert document and upload with correct owner
        val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
        val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!
        val hookRequest =
            createHookRequest(
                HookType.PostFinish,
                fileInfo = createFileInfo(metadata = createFileMetadata(filename, externalId), id = uploadId.toString()),
            )
        val response = tusService.postTerminate(hookRequest, personident)

        // Assert: should delete and return 204
        assertEquals(response.httpResponse.StatusCode, 204)
        val upload =
            dsl
                .selectFrom(UPLOAD)
                .where(
                    UPLOAD.ID
                        .eq(uploadId),
                ).fetchOne()
        assertNull(upload)
    }

    @Test
    fun `validateUpload should return errors if file is too large`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "largefile.pdf"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            // Store a large file in the file system (exceeds MAX_FILE_SIZE)
            val largeContent = ByteArray(20) { 1 }
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, largeContent)

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo =
                        createFileInfo(
                            metadata = createFileMetadata(filename, externalId),
                            id = uploadId.toString(),
                            size =
                                MAX_FILE_SIZE.toLong() + 1,
                        ),
                )

            val response = tusService.validateUpload(hookRequest)

            // Assert: should return 400 and error about file size
            assertEquals(response.httpResponse.StatusCode, 400)
            assertEquals(response.httpResponse.body?.contains("FILE_TOO_LARGE"), true)
            // Assert: error is recorded in DB
            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(
                        ERROR.UPLOAD
                            .eq(uploadId),
                    ).fetch()
            assertTrue(errors.isNotEmpty)
        }

    @Test
    fun `validateUpload should return 200 and no errors for valid non-pdf upload`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "validfile.pdf"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            val validContent = "Roflmao".toByteArray()
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, validContent)

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo =
                        createFileInfo(
                            metadata = createFileMetadata(filename, externalId),
                            id = uploadId.toString(),
                            size = validContent.size.toLong(),
                        ),
                )

            val response = tusService.validateUpload(hookRequest)

            assertEquals(200, response.httpResponse.StatusCode)
            assertTrue(response.httpResponse.body == null || response.httpResponse.body.isEmpty())
            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(
                        ERROR.UPLOAD
                            .eq(uploadId),
                    ).fetch()
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `validateUpload should return 200 and no errors for valid pdf upload`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "validfile.pdf"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            val validContent = this::class.java.getResource("/sample.pdf")!!.readBytes()
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, validContent)

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo =
                        createFileInfo(
                            metadata = createFileMetadata(filename, externalId),
                            id = uploadId.toString(),
                            size = validContent.size.toLong(),
                        ),
                )

            val response = tusService.validateUpload(hookRequest)

            assertEquals(200, response.httpResponse.StatusCode)
            assertTrue(response.httpResponse.body == null || response.httpResponse.body.isEmpty())
            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(
                        ERROR.UPLOAD
                            .eq(uploadId),
                    ).fetch()
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `validateUpload should return 200 and no errors with virus check`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "validfile.pdf"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            val validContent = "roflmao".toByteArray()
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, validContent)

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo =
                        createFileInfo(
                            metadata = createFileMetadata(filename, externalId),
                            id = uploadId.toString(),
                            size = validContent.size.toLong(),
                        ),
                )

            val response = tusService.validateUpload(hookRequest)

            assertEquals(200, response.httpResponse.StatusCode)
            assertTrue(response.httpResponse.body == null || response.httpResponse.body.isEmpty())
            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(
                        ERROR.UPLOAD
                            .eq(uploadId),
                    ).fetch()
            assertTrue(errors.isEmpty())
        }

    @Test
    fun `validateUpload should return 400 and make errors with virus infected file`() =
        runTest {
            val externalId = UUID.randomUUID().toString()
            val filename = "infected.pdf"
            val personident = UUID.randomUUID().toString().take(11)
            val tx = dsl.configuration()

            // Insert document and upload in DB
            val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
            val uploadId = uploadRepository.create(tx, documentId, filename, 1L)!!

            val validContent = "infected content".toByteArray()
            val filePath = tempDir.resolve(uploadId.toString())
            Files.write(filePath, validContent)

            // Prepare HookRequest
            val hookRequest =
                createHookRequest(
                    HookType.PostFinish,
                    fileInfo =
                        createFileInfo(
                            metadata = createFileMetadata(filename, externalId),
                            id = uploadId.toString(),
                            size = validContent.size.toLong(),
                        ),
                )

            validator = UploadValidator(virusScanner, storage)
            val response = tusService.validateUpload(hookRequest)

            assertEquals(400, response.httpResponse.StatusCode)
            assertContains(response.httpResponse.body ?: "", "POSSIBLY_INFECTED")
            val errors =
                dsl
                    .selectFrom(ERROR)
                    .where(
                        ERROR.UPLOAD
                            .eq(uploadId),
                    ).fetch()
                    .toList()
            assertTrue(errors.isNotEmpty())
        }
}
