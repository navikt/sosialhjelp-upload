package no.nav.sosialhjelp.upload.integration

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.di.DI
import io.ktor.server.plugins.di.OverridePrevious
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseAlreadyExistsException
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.TestUtils.awaitUploadTerminal
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.module
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import no.nav.sosialhjelp.upload.status.dto.UploadDto
import no.nav.sosialhjelp.upload.testutils.JwtTestUtils
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadFlowIntegrationTest {

    private val jwksServer = MockWebServer()
    private val mellomlagringClient = mockk<MellomlagringClient>(relaxed = true)
    private val fiksClient = mockk<FiksClient>(relaxed = true)

    @BeforeAll
    fun startJwks() {
        jwksServer.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .addHeader("Content-Type", "application/json")
                    .body(JwtTestUtils.jwksJson)
                    .build()
        }
        jwksServer.start()
    }

    @AfterAll
    fun stopJwks() {
        jwksServer.close()
    }

    @BeforeEach
    fun resetMocks() {
        clearMocks(mellomlagringClient, fiksClient)
    }

    private fun appConfig() = MapApplicationConfig(
        "runtimeEnv" to "test",
        "database.user" to PostgresTestContainer.username,
        "database.password" to PostgresTestContainer.password,
        "database.jdbcUrl" to PostgresTestContainer.jdbcUrl,
        "jwt.issuer" to JwtTestUtils.ISSUER,
        "jwt.jwks_uri" to jwksServer.url("/jwks").toString(),
        "jwt.audience" to JwtTestUtils.CLIENT_ID,
        "gotenberg.url" to "http://unused",
        "fiks.baseUrl" to "http://unused",
        "fiks.integrasjonsid" to "test-id",
        "fiks.integrasjonspassord" to "test-pass",
        "virus.scanner.url" to "",
        "texas.url" to "http://unused",
        "database.cleanOnStart" to "true",
        "tokenx.jwksUri" to "http://unused",
        "tokenx.issuer" to "whatever",
        "tokenx.clientId" to "whatever",
        )

    /**
     * Test harness using Ktor's mock engine. Suitable for all non-SSE tests.
     */
    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = appConfig() }
        application {
            // Overrides must be registered before the module is loaded
            dependencies.provide<MellomlagringClient> { mellomlagringClient }
            dependencies.provide<FiksClient> { fiksClient }
            dependencies.provide<CoroutineDispatcher> { Dispatchers.Unconfined }
            module()
        }
        startApplication()
        client = createClient {
            install(SSE)
        }
        block()
    }

    /**
     * Test harness using a real Netty server on a random port.
     * Required for SSE tests — Ktor's mock engine waits for the handler to return before
     * delivering the response, so streaming connections never get established.
     */
    private fun appTestReal(block: suspend CoroutineScope.(HttpClient) -> Unit) {
        val server = embeddedServer(
            factory = Netty,
            environment = applicationEnvironment { config = appConfig() },
            configure = {
                connector { port = 0 }
                responseWriteTimeoutSeconds = 0
            },
        ) {
            install(DI) { conflictPolicy = OverridePrevious }
            module()
            dependencies.provide<MellomlagringClient> { mellomlagringClient }
            dependencies.provide<FiksClient> { fiksClient }
            // Wrap to avoid DI trying to close Dispatchers.IO on shutdown
            dependencies.provide<CoroutineDispatcher> { Dispatchers.IO.limitedParallelism(Int.MAX_VALUE) }
        }
        server.start()
        val port = kotlinx.coroutines.runBlocking { server.engine.resolvedConnectors().first().port }
        val client = HttpClient(CIO) {
            install(SSE)
            install(DefaultRequest) { url("http://localhost:$port") }
            engine { requestTimeout = 0 }
        }
        try {
            kotlinx.coroutines.runBlocking {
                val scope = CoroutineScope(coroutineContext + SupervisorJob())
                try {
                    scope.block(client)
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            client.close()
            server.stop()
        }
    }

    /** Creates a minimal valid PDF using PDFBox. */
    private fun minimalPdf(): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray())

    /** Performs the 2-step TUS flow (POST + PATCH) and returns the upload UUID. */
    private suspend fun tusUpload(
        client: HttpClient,
        contextId: String,
        data: ByteArray,
        token: String,
        filename: String = "test.pdf",
    ): UUID {
        val createResp = client.post("/sosialhjelp/upload/tus/files") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
            header("Upload-Length", data.size.toString())
            header("Upload-Metadata", "filename ${base64(filename)}, contextId ${base64(contextId)}, fiksDigisosId ${base64(UUID.randomUUID().toString())}")
        }
        assertEquals(HttpStatusCode.Created, createResp.status, "TUS POST should return 201")

        val location = createResp.headers["Location"]!!
        val uploadId = UUID.fromString(location.substringAfterLast("/"))

        val patchResp = client.patch("/sosialhjelp/upload/tus/files/$uploadId") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
            header("Content-Type", "application/offset+octet-stream")
            header("Upload-Offset", "0")
            setBody(data)
        }
        assertEquals(HttpStatusCode.NoContent, patchResp.status, "TUS PATCH should return 204")

        return uploadId
    }

    /**
     * Opens an SSE connection for [contextId] and returns a [SharedFlow] of [SubmissionState]
     * events. The connection runs in the background until the scope is cancelled.
     * Must be called from within [appTestReal].
     */
    private fun CoroutineScope.sseEvents(
        client: HttpClient,
        contextId: String,
        token: String,
    ): SharedFlow<SubmissionState> {
        val flow = MutableSharedFlow<SubmissionState>(replay = 64)
        launch(Dispatchers.IO) {
            client.sse("/sosialhjelp/upload/status/$contextId?soknadId=test", {
                header("Authorization", "Bearer $token")
            }) {
                incoming.collect { event ->
                    event.data?.let { data ->
                        runCatching { Json.decodeFromString<SubmissionState>(data) }
                            .onSuccess { flow.emit(it) }
                    }
                }
            }
        }
        return flow.asSharedFlow()
    }

    @Test
    fun `TUS upload stores file in mellomlagring`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)

        val uploadId = tusUpload(client, contextId, minimalPdf(), token)

        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)

        val row = PostgresTestContainer.dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
        assertNotNull(row, "Upload row should exist in DB")
        assertEquals(filId, row[UPLOAD.FIL_ID], "FIL_ID should be set to mellomlagring ID")
        assertNull(row[UPLOAD.GCS_KEY], "gcs_key should be cleared after processing")

        coVerify { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
    }

    @Test
    fun `deleting uploaded file removes from DB and calls mellomlagring deleteFile`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)
        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)

        val deleteResp = client.delete("/sosialhjelp/upload/tus/files/$uploadId") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        val row = PostgresTestContainer.dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne()
        assertNull(row, "Upload row should be removed from DB after deletion")

        coVerify { mellomlagringClient.deleteFile(any(), filId) }
    }

    @Test
    fun `submit calls Fiks and deletes submission from DB`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        val fiksDigisosId = UUID.randomUUID().toString()

        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        val mockSak = mockk<DigisosSak> { every { kommunenummer } returns "0301" }
        coEvery { fiksClient.getSak(fiksDigisosId, any()) } returns mockSak
        val fiksResponse = mockk<HttpResponse> {
            every { status } returns HttpStatusCode.OK
        }
        coEvery { fiksClient.uploadEttersendelse(any(), any(), any(), any(), any(), any()) } returns fiksResponse

        val submissionId = createMockSubmission(PostgresTestContainer.dsl, contextId)
        val uploadId = tusUpload(client, contextId, minimalPdf(), token)

        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)

        val submitResp = client.post("/sosialhjelp/upload/submission/$submissionId/submit") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fiksDigisosId":"$fiksDigisosId","metadata":{"type":"dok","tilleggsinfo":"info"}}""")
        }
        assertEquals(HttpStatusCode.Created, submitResp.status)

        val sub = PostgresTestContainer.dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()
        assertNull(sub, "Submission should be deleted after successful submit")
    }

    @Test
    fun `submit returns 201 and cleans up when Fiks reports ettersendelse already exists`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        val fiksDigisosId = UUID.randomUUID().toString()

        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        val mockSak = mockk<DigisosSak> { every { kommunenummer } returns "0301" }
        coEvery { fiksClient.getSak(fiksDigisosId, any()) } returns mockSak
        coEvery {
            fiksClient.uploadEttersendelse(any(), any(), any(), any(), any(), any())
        } throws EttersendelseAlreadyExistsException("navEksternRefId-0001", fiksDigisosId)

        val submissionId = createMockSubmission(PostgresTestContainer.dsl, contextId)
        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)

        val submitResp = client.post("/sosialhjelp/upload/submission/$submissionId/submit") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fiksDigisosId":"$fiksDigisosId","metadata":{"type":"dok","tilleggsinfo":"info"}}""")
        }
        assertEquals(HttpStatusCode.Created, submitResp.status)

        val sub = PostgresTestContainer.dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()
        assertNull(sub, "Submission should be cleaned up even when Fiks reports already exists")
    }

    @Test
    fun `POST to contextId owned by another user returns Forbidden`() = appTest {
        val contextId = UUID.randomUUID().toString()
        createMockSubmission(PostgresTestContainer.dsl, contextId, ownerIdent = "11111111111")

        val token = JwtTestUtils.issueToken(subject = "22222222222")
        val resp = client.post("/sosialhjelp/upload/tus/files") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
            header("Upload-Length", "100")
            header("Upload-Metadata", "filename ${base64("test.pdf")}, contextId ${base64(contextId)}, fiksDigisosId ${base64(UUID.randomUUID().toString())}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `SSE stream emits updated state after TUS upload completes`() = appTestReal { client ->
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)

        val events = sseEvents(client, contextId, token)

        // Wait for the initial empty state before triggering the upload
        val initial = withTimeout(10.seconds) { events.first { it.uploads.isEmpty() } }
        assertTrue(initial.uploads.isEmpty(), "Initial SSE event should have no uploads")

        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)

        val completed = withTimeout(10.seconds) {
            events.first { event -> event.uploads.any { it.status == UploadDto.Status.COMPLETE } }
        }
        assertEquals(1, completed.uploads.size)
        assertEquals(filId, completed.uploads.first().filId)
        assertEquals(UploadDto.Status.COMPLETE, completed.uploads.first().status)
    }

    @Test
    fun `SSE stream reflects deleted upload`() = appTestReal { client ->
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)

        val events = sseEvents(client, contextId, token)

        withTimeout(10.seconds) { events.first { it.uploads.isEmpty() } } // initial state

        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)
        withTimeout(10.seconds) { events.first { it.uploads.isNotEmpty() } }

        client.delete("/sosialhjelp/upload/tus/files/$uploadId") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
        }

        // After delete the state should revert to empty uploads (skip the initial empty event we already consumed)
        val afterDelete = withTimeout(10.seconds) {
            events.filter { state: SubmissionState -> state.uploads.isEmpty() }.drop(1).first()
        }
        assertTrue(afterDelete.uploads.isEmpty(), "State after delete should have no uploads")
    }

    @Test
    fun `after submit and reconnect, new SSE stream receives updates for new uploads`() = appTestReal { client ->
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val fiksDigisosId = UUID.randomUUID().toString()

        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returnsMany
            listOf(UUID.randomUUID(), UUID.randomUUID())

        val mockSak = mockk<DigisosSak> { every { kommunenummer } returns "0301" }
        coEvery { fiksClient.getSak(fiksDigisosId, any()) } returns mockSak
        coEvery { fiksClient.uploadEttersendelse(any(), any(), any(), any(), any(), any()) } returns mockk<HttpResponse> {
            every { status } returns HttpStatusCode.OK
        }

        // 1. Create initial submission and connect SSE
        val submissionId = createMockSubmission(PostgresTestContainer.dsl, contextId)
        val events1 = sseEvents(client, contextId, token)
        withTimeout(10.seconds) { events1.first { it.uploads.isEmpty() } } // initial state

        // 2. Upload a file and wait for COMPLETE on the stream
        val uploadId1 = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId1)
        withTimeout(10.seconds) {
            events1.first { event -> event.uploads.any { it.status == UploadDto.Status.COMPLETE } }
        }

        // 3. Submit — deletes the submission
        val submitResp = client.post("/sosialhjelp/upload/submission/$submissionId/submit") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fiksDigisosId":"$fiksDigisosId","metadata":{"type":"dok","tilleggsinfo":"info"}}""")
        }
        assertEquals(HttpStatusCode.Created, submitResp.status)

        // 4. Reconnect — new stream, new submission
        val events2 = sseEvents(client, contextId, token)
        val reconnectInitial = withTimeout(10.seconds) { events2.first { it.uploads.isEmpty() } }
        assertTrue(reconnectInitial.uploads.isEmpty(), "Reconnect initial state should have no uploads")

        // 5. Upload a second file on the new submission
        val uploadId2 = tusUpload(client, contextId, minimalPdf(), token, filename = "second.pdf")
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId2)

        // 6. The reconnected stream should see the new upload reach COMPLETE
        val finalEvent = withTimeout(10.seconds) {
            events2.first { event -> event.uploads.any { it.status == UploadDto.Status.COMPLETE } }
        }
        assertEquals(1, finalEvent.uploads.size, "Reconnected stream should show exactly one upload")
        assertEquals(UploadDto.Status.COMPLETE, finalEvent.uploads.first().status)
    }

    @Test
    fun `submit returns 201 when Fiks rejects duplicate file names with 400`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val fiksDigisosId = UUID.randomUUID().toString()

        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returnsMany
            listOf(UUID.randomUUID(), UUID.randomUUID())

        val mockSak = mockk<DigisosSak> { every { kommunenummer } returns "0301" }
        coEvery { fiksClient.getSak(fiksDigisosId, any()) } returns mockSak

        // Fiks returns 400 Bad Request when two files share the same file name
        coEvery { fiksClient.uploadEttersendelse(any(), any(), any(), any(), any(), any()) } answers {
            val filer = arg<List<no.nav.sosialhjelp.upload.action.fiks.Fil>>(5)
            val filenames = filer.map { it.filnavn }
            mockk<HttpResponse> {
                every { status } returns if (filenames.size != filenames.distinct().size) {
                    HttpStatusCode.BadRequest
                } else {
                    HttpStatusCode.OK
                }
            }
        }

        val submissionId = createMockSubmission(PostgresTestContainer.dsl, contextId)

        // Upload two files with the same name — this is what triggers the 400 from Fiks
        val uploadId1 = tusUpload(client, contextId, minimalPdf(), token, filename = "document.pdf")
        val uploadId2 = tusUpload(client, contextId, minimalPdf(), token, filename = "document.pdf")
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId1)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId2)

        val submitResp = client.post("/sosialhjelp/upload/submission/$submissionId/submit") {
            header("Authorization", "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"fiksDigisosId":"$fiksDigisosId","metadata":{"type":"dok","tilleggsinfo":"info"}}""")
        }
        assertEquals(HttpStatusCode.Created, submitResp.status)
    }

    @Test
    fun `SSE endpoint responds with event-stream content type`() = appTestReal { client ->
        val contextId = UUID.randomUUID().toString()
        val soknadId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()

        data class Headers(val status: HttpStatusCode, val contentType: String?)

        val deferredHeaders = async {
            client.prepareGet("/sosialhjelp/upload/status/$contextId?soknadId=$soknadId") {
                header("Authorization", "Bearer $token")
            }.execute { response ->
                Headers(response.status, response.headers[HttpHeaders.ContentType])
            }
        }
        val (statusCode, contentTypeHeader) = deferredHeaders.await()
        // Cancel the SSE connection — the server-side handler will run indefinitely otherwise.
        deferredHeaders.cancel()
        assertEquals(HttpStatusCode.OK, statusCode)
        assertEquals(contentTypeHeader?.contains("text/event-stream"), true, "Expected text/event-stream but got: $contentTypeHeader")
    }
}
