package no.nav.sosialhjelp.upload.files

import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.module
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FileRoutesIntegrationTest {
    private val jwksServer = MockWebServer()
    private val mellomlagringClient = mockk<MellomlagringClient>(relaxed = true)
    private val fiksClient = mockk<FiksClient>(relaxed = true)
    private val queries = spyk(FileUploadQueries())

    @BeforeAll
    fun startJwks() {
        jwksServer.dispatcher =
            object : Dispatcher() {
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
    fun stopJwks() = jwksServer.close()

    @BeforeEach
    fun reset() {
        clearMocks(mellomlagringClient, fiksClient, queries)
    }

    private fun appTest(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
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
                        "database.cleanOnStart" to "true",
                        "tokenx.jwksUri" to "http://unused",
                        "tokenx.issuer" to "whatever",
                        "tokenx.clientId" to "whatever",
                    )
            }
            application { overrides() }
            startApplication()
            block()
        }

    private fun Application.overrides() {
        dependencies.provide<MellomlagringClient> { mellomlagringClient }
        dependencies.provide<FiksClient> { fiksClient }
        dependencies.provide<FileUploadQueries> { queries }
        dependencies.provide<CoroutineDispatcher> { Dispatchers.Unconfined }
        module()
    }

    private fun pdf(): ByteArray {
        val doc = PDDocument()
        doc.addPage(PDPage())
        val out = ByteArrayOutputStream()
        doc.save(out)
        doc.close()
        return out.toByteArray()
    }

    private suspend fun ApplicationTestBuilder.postFile(
        contextId: String,
        correlationId: UUID?,
        data: ByteArray = pdf(),
        filename: String = "test.pdf",
        token: String = JwtTestUtils.issueToken(),
    ) = client.submitFormWithBinaryData(
        "/sosialhjelp/upload/files",
        formData {
            append("contextId", contextId)
            append("fiksDigisosId", UUID.randomUUID().toString())
            correlationId?.let { append("correlationId", it.toString()) }
            append(
                "file",
                data,
                Headers.build {
                    append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                },
            )
        },
    ) { header("Authorization", "Bearer $token") }

    private fun rows(correlationId: UUID) =
        PostgresTestContainer.dsl
            .selectFrom(UPLOAD)
            .where(UPLOAD.CORRELATION_ID.eq(correlationId))
            .fetch()

    private fun stubUpload(filId: UUID = UUID.randomUUID()): UUID {
        coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } returns filId
        return filId
    }

    @Test
    fun `201 and row written`() =
        appTest {
            val filId = stubUpload()
            val corr = UUID.randomUUID()
            val resp = postFile(UUID.randomUUID().toString(), corr)
            assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
            val row = rows(corr).single()
            assertEquals(filId, row[UPLOAD.FIL_ID])
            assertEquals("COMPLETE", row[UPLOAD.PROCESSING_STATUS])
            assertTrue(resp.bodyAsText().contains("COMPLETE"))
        }

    @Test
    fun `idempotent replay returns 200 without second upload`() =
        appTest {
            stubUpload()
            val corr = UUID.randomUUID()
            val ctx = UUID.randomUUID().toString()
            assertEquals(HttpStatusCode.Created, postFile(ctx, corr).status)
            assertEquals(HttpStatusCode.OK, postFile(ctx, corr).status)
            assertEquals(1, rows(corr).size)
            coVerify(exactly = 1) { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
        }

    @Test
    fun `422 on validation error leaves no row`() =
        appTest {
            val corr = UUID.randomUUID()
            val resp =
                postFile(
                    UUID.randomUUID().toString(),
                    corr,
                    data = "%PDF-1.4\nnot really a pdf".toByteArray(),
                    filename = "x.pdf",
                )
            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status, resp.bodyAsText())
            assertTrue(rows(corr).isEmpty())
            coVerify(exactly = 0) { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
        }

    @Test
    fun `502 when mellomlagring fails leaves no row`() =
        appTest {
            coEvery { mellomlagringClient.uploadFile(any(), any(), any(), any()) } throws RuntimeException("boom")
            val corr = UUID.randomUUID()
            val resp = postFile(UUID.randomUUID().toString(), corr)
            assertEquals(HttpStatusCode.BadGateway, resp.status)
            assertTrue(resp.bodyAsText().contains("STORAGE"))
            assertTrue(rows(corr).isEmpty())
        }

    @Test
    fun `403 when submission owned by another user`() =
        appTest {
            stubUpload()
            val ctx = UUID.randomUUID().toString()
            assertEquals(HttpStatusCode.Created, postFile(ctx, UUID.randomUUID()).status)
            val other = JwtTestUtils.issueToken(subject = "99999999999")
            assertEquals(HttpStatusCode.Forbidden, postFile(ctx, UUID.randomUUID(), token = other).status)
        }

    @Test
    fun `400 without correlationId`() =
        appTest {
            assertEquals(HttpStatusCode.BadRequest, postFile(UUID.randomUUID().toString(), null).status)
        }

    @Test
    fun `413 when body too large`() =
        appTest {
            val resp = postFile(UUID.randomUUID().toString(), UUID.randomUUID(), data = ByteArray(11 * 1024 * 1024))
            assertEquals(HttpStatusCode.PayloadTooLarge, resp.status)
        }

    @Test
    fun `400 with two file parts`() =
        appTest {
            val resp =
                client.submitFormWithBinaryData(
                    "/sosialhjelp/upload/files",
                    formData {
                        append("contextId", UUID.randomUUID().toString())
                        append("correlationId", UUID.randomUUID().toString())
                        repeat(2) {
                            append(
                                "file",
                                pdf(),
                                Headers.build {
                                    append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                                    append(HttpHeaders.ContentDisposition, "filename=\"f$it.pdf\"")
                                },
                            )
                        }
                    },
                ) { header("Authorization", "Bearer ${JwtTestUtils.issueToken()}") }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            coVerify(exactly = 0) { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
        }

    @Test
    fun `failed insert deletes the stored file`() =
        appTest {
            val filId = stubUpload()
            every { queries.insertCompleted(any(), any(), any(), any(), any(), any(), any()) } throws
                RuntimeException("db down")
            val corr = UUID.randomUUID()
            val resp = postFile(UUID.randomUUID().toString(), corr)
            assertEquals(HttpStatusCode.InternalServerError, resp.status)
            assertTrue(rows(corr).isEmpty())
            coVerify { mellomlagringClient.deleteFile(any(), filId, true) }
        }

    private suspend fun ApplicationTestBuilder.upload(
        ctx: String = UUID.randomUUID().toString(),
        token: String = JwtTestUtils.issueToken(),
    ): Pair<UUID, UUID> {
        val corr = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, postFile(ctx, corr, token = token).status)
        return rows(corr).single()[UPLOAD.ID]!! to corr
    }

    private suspend fun ApplicationTestBuilder.delete(
        id: UUID,
        token: String = JwtTestUtils.issueToken(),
    ) = client.delete("/sosialhjelp/upload/files/$id") { header("Authorization", "Bearer $token") }

    @Test
    fun `delete removes row then file`() =
        appTest {
            val filId = stubUpload()
            val (id, corr) = upload()
            assertEquals(HttpStatusCode.NoContent, delete(id).status)
            assertTrue(rows(corr).isEmpty())
            coVerify { mellomlagringClient.deleteFile(any(), filId, true) }
        }

    @Test
    fun `delete still removes row when mellomlagring fails`() =
        appTest {
            stubUpload()
            val (id, corr) = upload()
            coEvery { mellomlagringClient.deleteFile(any(), any(), any()) } throws RuntimeException("boom")
            assertEquals(HttpStatusCode.NoContent, delete(id).status)
            assertTrue(rows(corr).isEmpty())
        }

    @Test
    fun `delete returns 404 for other user and after deletion`() =
        appTest {
            stubUpload()
            val (id, corr) = upload()
            val other = JwtTestUtils.issueToken(subject = "99999999999")
            assertEquals(HttpStatusCode.NotFound, delete(id, other).status)
            assertEquals(1, rows(corr).size)
            assertEquals(HttpStatusCode.NoContent, delete(id).status)
            assertEquals(HttpStatusCode.NotFound, delete(id).status)
        }

    @Test
    fun `download of file uploaded through new endpoint`() =
        appTest {
            stubUpload()
            val (id, _) = upload()
            val bytes = pdf()
            coEvery { mellomlagringClient.getFile(any(), any()) } returns bytes
            val resp =
                client.get("/sosialhjelp/upload/upload/$id") {
                    header("Authorization", "Bearer ${JwtTestUtils.issueToken()}")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertTrue(resp.readRawBytes().contentEquals(bytes))
        }

    private suspend fun ApplicationTestBuilder.status(
        ctx: String,
        token: String = JwtTestUtils.issueToken(),
    ) = client.get("/sosialhjelp/upload/submission/context/$ctx") { header("Authorization", "Bearer $token") }

    @Test
    fun `status returns empty state without creating a submission`() =
        appTest {
            val ctx = UUID.randomUUID().toString()
            val resp = status(ctx)
            assertEquals(HttpStatusCode.OK, resp.status)
            assertEquals("no-store", resp.headers[HttpHeaders.CacheControl])
            val uploads = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["uploads"]!!.jsonArray
            assertTrue(uploads.isEmpty())
            assertEquals(0, PostgresTestContainer.dsl.fetchCount(SUBMISSION, SUBMISSION.CONTEXT_ID.eq(ctx)))
        }

    @Test
    fun `status lists uploads and 403 for other owner`() =
        appTest {
            stubUpload()
            val ctx = UUID.randomUUID().toString()
            val (id, _) = upload(ctx)
            val body = status(ctx).bodyAsText()
            assertTrue(body.contains(id.toString()), body)
            assertTrue(body.contains("COMPLETE"))
            assertEquals(HttpStatusCode.Forbidden, status(ctx, JwtTestUtils.issueToken(subject = "99999999999")).status)
        }

    @Test
    fun `prepare validation runs outside the shared lock`() =
        appTest {
            val navEksternRefId = "ref-locked-${UUID.randomUUID()}"
            coEvery { fiksClient.getNewNavEksternRefId(any(), any(), any()) } returns navEksternRefId
            val corr = UUID.randomUUID()

            val resp =
                PostgresTestContainer.lock.exclusive(navEksternRefId) {
                    postFile(
                        UUID.randomUUID().toString(),
                        corr,
                        data = "%PDF-1.4\nnot really a pdf".toByteArray(),
                        filename = "x.pdf",
                    )
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, resp.status, resp.bodyAsText())
            coVerify(exactly = 0) { mellomlagringClient.uploadFile(any(), any(), any(), any()) }
        }
}
