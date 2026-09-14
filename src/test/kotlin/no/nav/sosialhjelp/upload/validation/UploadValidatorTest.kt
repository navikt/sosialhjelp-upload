package no.nav.sosialhjelp.upload.validation

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UploadValidatorTest {
    private val virusScanner = mockk<VirusScanner>()
    private val validator = UploadValidator(virusScanner, SimpleMeterRegistry())

    @Test
    fun `detects BMP bytes even when filename has jpg extension`() =
        runTest {
            coEvery { virusScanner.scan(any()) } returns Result.OK

            val result = validator.validate("photo.jpg", minimalBmp(), 58)

            assertEquals("image/bmp", result.mimeType)
            assertTrue(result.errors.isEmpty())
        }

    private fun minimalBmp(): ByteArray =
        ByteArray(58).apply {
            this[0] = 'B'.code.toByte()
            this[1] = 'M'.code.toByte()
            this[2] = 58
            this[10] = 54
            this[14] = 40
            this[18] = 1
            this[22] = 1
            this[26] = 1
            this[28] = 24
        }
}
