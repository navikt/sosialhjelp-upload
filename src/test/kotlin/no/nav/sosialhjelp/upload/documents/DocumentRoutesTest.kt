package no.nav.sosialhjelp.upload.documents

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.mockk
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.module
import no.nav.sosialhjelp.upload.testutils.JwtTestUtils
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRoutesTest {

    private val jwksServer = MockWebServer()
    private val mellomlagringClient = mockk<MellomlagringClient>(relaxed = true)
    private val fiksClient = mockk<FiksClient>(relaxed = true)
    private lateinit var dsl: DSLContext

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
        PostgresTestContainer.migrate()
        dsl = PostgresTestContainer.dsl
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
            dependencies.provide<MellomlagringClient> { mellomlagringClient }
            dependencies.provide<FiksClient> { fiksClient }
            module()
        }
        startApplication()
        block()
    }

    /** Creates an upload row in a COMPLETE state with all required fields set. */
    private fun createCompletedUpload(submissionId: UUID, owner: String = "12345678910"): UUID {
        val uploadId = UUID.randomUUID()
        val gcsKey = "uploads/$uploadId"
        dsl.transactionResult { tx ->
            tx.dsl()
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, uploadId)
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, "test.pdf")
                .set(UPLOAD.SIZE, 100L)
                .set(UPLOAD.UPLOAD_OFFSET, 100L)
                .set(UPLOAD.GCS_KEY, gcsKey)
                .set(UPLOAD.FIL_ID, UUID.randomUUID())
                .set(UPLOAD.MELLOMLAGRING_FILNAVN, "test.pdf")
                .set(UPLOAD.MELLOMLAGRING_STORRELSE, 100L)
                .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
                .execute()
        }
        return uploadId
    }

    @Test
    fun `returns 401 when no token`() = appTest {
        val submissionId = createMockSubmission(dsl)
        val uploadId = createCompletedUpload(submissionId)

        client.get("/sosialhjelp/upload/upload/$uploadId").apply {
            assertEquals(HttpStatusCode.Unauthorized, status)
        }
    }

    @Test
    fun `returns 403 when user does not own upload`() = appTest {
        val ownerToken = JwtTestUtils.issueToken(subject = "11111111111")
        val otherToken = JwtTestUtils.issueToken(subject = "22222222222")

        val submissionId = createMockSubmission(dsl, ownerIdent = "11111111111")
        val uploadId = createCompletedUpload(submissionId, owner = "11111111111")

        coEvery { mellomlagringClient.getFile(any(), any()) } returns ByteArray(0)

        client.get("/sosialhjelp/upload/upload/$uploadId") {
            header("Authorization", "Bearer $otherToken")
        }.apply {
            assertEquals(HttpStatusCode.Forbidden, status)
        }
    }

    @Test
    fun `returns 200 when user owns upload`() = appTest {
        val token = JwtTestUtils.issueToken(subject = "12345678910")
        val submissionId = createMockSubmission(dsl, ownerIdent = "12345678910")
        val uploadId = createCompletedUpload(submissionId, owner = "12345678910")

        coEvery { mellomlagringClient.getFile(any(), any()) } returns "hello".toByteArray()

        client.get("/sosialhjelp/upload/upload/$uploadId") {
            header("Authorization", "Bearer $token")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
