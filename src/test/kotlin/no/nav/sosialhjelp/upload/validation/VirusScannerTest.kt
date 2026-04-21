package no.nav.sosialhjelp.upload.validation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class VirusScannerTest {

    private lateinit var server: MockWebServer
    private lateinit var scanner: VirusScanner
    private val meterRegistry = SimpleMeterRegistry()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        scanner = VirusScanner(
            url = server.url("/scan").toString(),
            meterRegistry = meterRegistry,
        )
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `returns OK when scanner reports no virus`() = runTest {
        server.enqueue(
            MockResponse()
                .newBuilder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""[{"Filename":"test.pdf","Result":"OK"}]""")
                .build(),
        )
        assertEquals(Result.OK, scanner.scan("hello".toByteArray()))
    }

    @Test
    fun `returns FOUND when scanner reports virus`() = runTest {
        server.enqueue(
            MockResponse()
                .newBuilder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""[{"Filename":"test.pdf","Result":"FOUND"}]""")
                .build(),
        )
        assertEquals(Result.FOUND, scanner.scan("eicar".toByteArray()))
    }

    @Test
    fun `returns ERROR when scanner returns HTTP 500`() = runTest {
        server.enqueue(
            MockResponse()
                .newBuilder()
                .code(500)
                .build(),
        )
        assertEquals(Result.ERROR, scanner.scan("data".toByteArray()))
    }

    @Test
    fun `returns ERROR when scanner is unreachable`() = runTest {
        // Point at a closed server
        val closedScanner = VirusScanner(
            url = "http://localhost:1",
            meterRegistry = meterRegistry,
        )
        assertEquals(Result.ERROR, closedScanner.scan("data".toByteArray()))
    }

    @Test
    fun `returns OK when URL is empty (scanner not configured)`() = runTest {
        val unconfiguredScanner = VirusScanner(url = "", meterRegistry = meterRegistry)
        assertEquals(Result.OK, unconfiguredScanner.scan("data".toByteArray()))
    }
}
