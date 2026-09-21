package no.nav.sosialhjelp.upload.pdf

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GotenbergServiceTest {
    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF
    private lateinit var server: MockWebServer
    private lateinit var service: GotenbergService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = GotenbergService(server.url("/forms/libreoffice/convert").toString())
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `GotenbergConversionResult UnsupportedFiletype carries the extension`() {
        val result = GotenbergConversionResult.UnsupportedFiletype("xyz")
        assert(result.extension == "xyz")
    }

    @Test
    fun `retries server errors before returning converted PDF`() =
        runTest {
            server.enqueue(MockResponse().newBuilder().code(503).build())
            server.enqueue(MockResponse().newBuilder().code(503).build())
            server.enqueue(
                MockResponse()
                    .newBuilder()
                    .code(200)
                    .body("%PDF")
                    .build(),
            )

            val result = service.convertToPdf(byteArrayOf(1, 2, 3), "docx")
            assertInstanceOf(GotenbergConversionResult.Success::class.java, result)
            assertArrayEquals(pdfBytes, (result as GotenbergConversionResult.Success).bytes)
            repeat(3) { server.takeRequest() }
        }

    @Test
    fun `does not retry unsupported file types`() =
        runTest {
            server.enqueue(MockResponse().newBuilder().code(400).build())

            val result = service.convertToPdf(byteArrayOf(1, 2, 3), "xyz")
            assertInstanceOf(GotenbergConversionResult.UnsupportedFiletype::class.java, result)
            assert((result as GotenbergConversionResult.UnsupportedFiletype).extension == "xyz")
            server.takeRequest()
            assert(server.requestCount == 1)
        }

    @Test
    fun `throws after retry attempts are exhausted`() =
        runTest {
            repeat(3) { server.enqueue(MockResponse().newBuilder().code(500).build()) }

            assertThrows<IllegalStateException> {
                service.convertToPdf(byteArrayOf(1, 2, 3), "docx")
            }
            repeat(3) { server.takeRequest() }
        }
}
