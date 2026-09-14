package no.nav.sosialhjelp.upload.upload

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.pdf.GotenbergConversionResult
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileConversionServiceTest {
    private val gotenbergService = mockk<GotenbergService>()
    private val service = FileConversionService(gotenbergService)

    @Test
    fun `converts BMP bytes renamed to pdf using BMP format hint`() =
        runTest {
            val source = byteArrayOf(0x42, 0x4d)
            val converted = "pdf".toByteArray()
            coEvery { gotenbergService.convertToPdf(source, "bmp") } returns
                GotenbergConversionResult.Success(converted)

            val result = service.convertIfNeeded("photo.pdf", "image/bmp", source)

            assertEquals(
                FileConversionService.ConversionResult.Success(
                    "photo.pdf",
                    converted,
                    "application/pdf",
                    converted = true,
                ),
                result,
            )
            coVerify(exactly = 1) { gotenbergService.convertToPdf(source, "bmp") }
        }

    @Test
    fun `passes through JPEG bytes renamed to bmp with JPEG filename and content type`() =
        runTest {
            val source = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

            val result = service.convertIfNeeded("photo.bmp", "image/jpeg", source)

            assertEquals(
                FileConversionService.ConversionResult.Success("photo.jpg", source, "image/jpeg", converted = false),
                result,
            )
            coVerify(exactly = 0) { gotenbergService.convertToPdf(any(), any()) }
        }

    @Test
    fun `keeps a valid JPEG extension`() =
        runTest {
            val source = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())

            val result = service.convertIfNeeded("photo.jpeg", "image/jpeg", source)

            assertEquals(
                FileConversionService.ConversionResult.Success("photo.jpeg", source, "image/jpeg", converted = false),
                result,
            )
        }
}
