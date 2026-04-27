package no.nav.sosialhjelp.upload.integration

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.*
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.client.statement.*
import io.ktor.utils.io.readUTF8Line
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.TestUtils.awaitSseEvent
import no.nav.sosialhjelp.upload.common.TestUtils.awaitSseEventCount
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
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

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
        "runtimeEnv" to "local",
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
    )

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = appConfig() }
        application {
            // Overrides must be registered before the module is loaded
            dependencies.provide<MellomlagringClient> { mellomlagringClient }
            dependencies.provide<FiksClient> { fiksClient }
            module()
        }
        startApplication()
        block()
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

    /**
     * Opens an SSE connection for [contextId] and collects [SubmissionState] events into a
     * synchronized list until the returned job is cancelled.
     *
     * The job runs on [Dispatchers.IO] so that [awaitUploadTerminal] (which uses Thread.sleep)
     * can run concurrently on the calling coroutine without blocking the SSE reader.
     */
    private fun ApplicationTestBuilder.collectSseEvents(
        contextId: String,
        token: String,
    ): Pair<kotlinx.coroutines.Job, MutableList<SubmissionState>> {
        val events = Collections.synchronizedList(mutableListOf<SubmissionState>())
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            runCatching {
                client.prepareGet("/sosialhjelp/upload/status/$contextId?soknadId=test") {
                    header("Authorization", "Bearer $token")
                }.execute { response ->
                    val channel = response.bodyAsChannel()
                    while (isActive && !channel.isClosedForRead) {
                        val line = channel.readUTF8Line() ?: break
                        if (line.startsWith("data: ")) {
                            runCatching { Json.decodeFromString<SubmissionState>(line.removePrefix("data: ")) }
                                .onSuccess { events.add(it) }
                        }
                    }
                }
            }
        }
        return job to events
    }

    @Test
    fun `SSE stream emits updated state after TUS upload completes`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)

        val (sseJob, events) = collectSseEvents(contextId, token)
        // Wait for SSE to connect and receive the initial empty state
        awaitSseEventCount(events, 1, description = "initial SSE event")

        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)
        // Wait for Postgres NOTIFY to propagate to the SSE listener with COMPLETE status
        awaitSseEvent(events, description = "COMPLETE SSE event") { event ->
            event.uploads.any { it.status == UploadDto.Status.COMPLETE }
        }

        sseJob.cancel()

        assertTrue(events.size >= 2, "Expected ≥2 SSE events (initial state + post-upload), got ${events.size}")

        val initial = events.first()
        assertTrue(initial.uploads.isEmpty(), "Initial SSE event should have no uploads")

        val final = events.last { it.uploads.any { u -> u.status == UploadDto.Status.COMPLETE } }
        assertEquals(1, final.uploads.size, "Final SSE event should contain the uploaded file")
        assertEquals(filId, final.uploads.first().filId, "Upload filId should match mellomlagring response")
        assertEquals(UploadDto.Status.COMPLETE, final.uploads.first().status)
    }

    @Test
    fun `SSE stream reflects deleted upload`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()
        val filId = UUID.randomUUID()
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId

        createMockSubmission(PostgresTestContainer.dsl, contextId)

        val (sseJob, events) = collectSseEvents(contextId, token)
        delay(300.milliseconds)

        // Upload
        val uploadId = tusUpload(client, contextId, minimalPdf(), token)
        awaitUploadTerminal(PostgresTestContainer.dsl, uploadId)
        delay(300.milliseconds)

        // Delete
        client.delete("/sosialhjelp/upload/tus/files/$uploadId") {
            header("Authorization", "Bearer $token")
            header("Tus-Resumable", "1.0.0")
        }
        delay(500.milliseconds)

        sseJob.cancel()

        val statesWithUpload = events.filter { it.uploads.isNotEmpty() }
        val statesWithoutUpload = events.filter { it.uploads.isEmpty() }

        assertTrue(statesWithUpload.isNotEmpty(), "Should have received a state showing the upload")
        // After delete the state reverts to empty uploads
        assertTrue(
            statesWithoutUpload.size >= 2,
            "Should have received at least 2 empty-upload states (initial + post-delete)",
        )
        // The very last event should have no uploads
        assertTrue(events.last().uploads.isEmpty(), "Final SSE event after delete should have no uploads")
    }

    @Test
    fun `submit returns error when Fiks rejects duplicate file names with 400`() = appTest {
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
    fun `SSE endpoint responds with event-stream content type`() = appTest {
        val contextId = UUID.randomUUID().toString()
        val soknadId = UUID.randomUUID().toString()
        val token = JwtTestUtils.issueToken()

        var statusCode: HttpStatusCode? = null
        var contentTypeHeader: String? = null
        // prepareGet().execute gives access to response headers without consuming the streaming body
        client.prepareGet("/sosialhjelp/upload/status/$contextId?soknadId=$soknadId") {
            header("Authorization", "Bearer $token")
        }.execute { response ->
            statusCode = response.status
            contentTypeHeader = response.headers[HttpHeaders.ContentType]
        }

        assertEquals(HttpStatusCode.OK, statusCode)
        assertEquals(contentTypeHeader?.contains("text/event-stream"), true, "Expected text/event-stream but got: $contentTypeHeader")
    }
}
