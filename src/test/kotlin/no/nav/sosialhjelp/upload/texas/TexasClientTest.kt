package no.nav.sosialhjelp.upload.texas

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TexasClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TexasClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TexasClient(texasUrl = server.url("/token").toString())
    }

    @AfterEach
    fun tearDown() {
        server.close()
        client.client.close()
    }

    private fun successResponse(token: String = "my-token", expiresIn: Int = 3600) =
        MockResponse()
            .newBuilder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body("""{"access_token":"$token","expires_in":$expiresIn,"token_type":"Bearer"}""")
            .build()

    @Test
    fun `returns access token on success`() = runTest {
        server.enqueue(successResponse("abc123"))
        val token = client.getMaskinportenToken()
        assertEquals("abc123", token)
    }

    @Test
    fun `caches token and does not make second HTTP request within expiry`() = runTest {
        server.enqueue(successResponse("cached-token", expiresIn = 3600))
        val first = client.getMaskinportenToken()
        val second = client.getMaskinportenToken()
        assertEquals("cached-token", first)
        assertEquals("cached-token", second)
        // Only one request should have been made
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `fetches new token after expiry`() = runTest {
        // Issue a token that expires immediately (0s minus 30s buffer = already expired)
        server.enqueue(successResponse("first-token", expiresIn = 0))
        server.enqueue(successResponse("second-token", expiresIn = 3600))

        val first = client.getMaskinportenToken()
        // Force cache invalidation by manipulating expiry (expiresIn=0 means tokenExpiresAt is in the past)
        val second = client.getMaskinportenToken()

        assertEquals("first-token", first)
        assertEquals("second-token", second)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `throws when server returns error`() = runTest {
        server.enqueue(
            MockResponse()
                .newBuilder()
                .code(500)
                .addHeader("Content-Type", "application/json")
                .body("""{"error":{"error":"server_error","error_description":"Something went wrong"},"status":500}""")
                .build(),
        )
        assertFailsWith<RuntimeException> {
            client.getMaskinportenToken()
        }
    }
}
