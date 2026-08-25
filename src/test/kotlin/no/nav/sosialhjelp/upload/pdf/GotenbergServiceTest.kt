package no.nav.sosialhjelp.upload.pdf

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class GotenbergServiceTest {
    private val pdfBytes = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF

    @Test
    fun `GotenbergConversionResult UnsupportedFiletype carries the extension`() {
        val result = GotenbergConversionResult.UnsupportedFiletype("xyz")
        assert(result.extension == "xyz")
    }

    @Test
    fun `convertToPdf mock returns Success with bytes`() =
        runTest {
            val service = mockk<GotenbergService>()
            coEvery { service.convertToPdf(any(), any()) } returns GotenbergConversionResult.Success(pdfBytes)

            val result = service.convertToPdf(byteArrayOf(1, 2, 3), "docx")
            assertInstanceOf(GotenbergConversionResult.Success::class.java, result)
            assertArrayEquals(pdfBytes, (result as GotenbergConversionResult.Success).bytes)
        }

    @Test
    fun `convertToPdf mock returns UnsupportedFiletype for unsupported extension`() =
        runTest {
            val service = mockk<GotenbergService>()
            coEvery { service.convertToPdf(any(), any()) } returns GotenbergConversionResult.UnsupportedFiletype("xyz")

            val result = service.convertToPdf(byteArrayOf(1, 2, 3), "xyz")
            assertInstanceOf(GotenbergConversionResult.UnsupportedFiletype::class.java, result)
            assert((result as GotenbergConversionResult.UnsupportedFiletype).extension == "xyz")
        }

    @Test
    fun `convertToPdf mock throws IllegalStateException on server error`() =
        runTest {
            val service = mockk<GotenbergService>()
            coEvery { service.convertToPdf(any(), any()) } throws
                IllegalStateException("Failed to convert file type docx to PDF: 500 Internal Server Error")

            assertThrows<IllegalStateException> {
                service.convertToPdf(byteArrayOf(1, 2, 3), "docx")
            }
        }
}
