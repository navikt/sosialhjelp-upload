package no.nav.sosialhjelp.upload.pdf

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EttersendelsePdfGeneratorTest {
    private fun generate(
        type: String = "Faktura",
        vararg filenames: String,
    ): ByteArray =
        EttersendelsePdfGenerator.generate(
            PdfMetadata(type = type, filer = filenames.map { PdfFil(it) }),
            fodselsnummer = "12345678901",
        )

    @Test
    fun `generate produces non-empty PDF for Latin filename`() {
        val result = generate(filenames = arrayOf("dokument.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for Arabic filename`() {
        val result = generate(type = "وثيقة", filenames = arrayOf("ملف اختبار.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for mixed Arabic and Latin filename`() {
        val result = generate(filenames = arrayOf("document وثيقة test.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for Norwegian special characters`() {
        val result = generate(type = "Kvittering", filenames = arrayOf("søknad ærlig ål.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate does not throw for CJK filename`() {
        val result = generate(filenames = arrayOf("文档.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate does not throw for emoji filename`() {
        val result = generate(filenames = arrayOf("rapport 😀.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate does not throw for mixed Latin CJK and Arabic in one filename`() {
        val result = generate(filenames = arrayOf("faktura 文档 وثيقة receipt.pdf"))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate does not throw for long CJK filename that forces line wrapping`() {
        val longCjk = "文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档文档.pdf"
        val result = generate(filenames = arrayOf(longCjk))
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate never throws regardless of arbitrary unicode input`() {
        val weird =
            listOf(
                "\u0000\u0001\u001F",
                "\uD83D\uDE00\uD83D\uDCA9",
                "Thai: สวัสดี",
                "Devanagari: नमस्ते",
                "Cyrillic: Привет",
                "Hiragana: こんにちは",
            )
        for (input in weird) {
            val result = generate(filenames = arrayOf(input))
            assertTrue(result.isNotEmpty(), "Expected non-empty PDF for input: $input")
        }
    }
}
