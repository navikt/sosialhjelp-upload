package no.nav.sosialhjelp.upload.pdf

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EttersendelsePdfGeneratorTest {
    @Test
    fun `generate produces non-empty PDF for Latin filename`() {
        val result =
            EttersendelsePdfGenerator.generate(
                PdfMetadata(type = "Faktura", filer = listOf(PdfFil("dokument.pdf"))),
                fodselsnummer = "12345678901",
            )
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for Arabic filename`() {
        val result =
            EttersendelsePdfGenerator.generate(
                PdfMetadata(type = "وثيقة", filer = listOf(PdfFil("ملف اختبار.pdf"))),
                fodselsnummer = "12345678901",
            )
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for mixed Arabic and Latin filename`() {
        val result =
            EttersendelsePdfGenerator.generate(
                PdfMetadata(type = "Faktura", filer = listOf(PdfFil("document وثيقة test.pdf"))),
                fodselsnummer = "12345678901",
            )
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `generate produces non-empty PDF for Norwegian special characters`() {
        val result =
            EttersendelsePdfGenerator.generate(
                PdfMetadata(type = "Kvittering", filer = listOf(PdfFil("søknad ærlig ål.pdf"))),
                fodselsnummer = "12345678901",
            )
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }
}
