package no.nav.sosialhjelp.upload.action.fiks

import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.api.fiks.Ettersendelse
import no.nav.sosialhjelp.api.fiks.EttersendtInfoNAV
import no.nav.sosialhjelp.api.fiks.OriginalSoknadNAV
import no.nav.sosialhjelp.api.fiks.DokumentInfo
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FiksClientTest {

    private fun digisosSak(
        fiksDigisosId: String = "fiks-id",
        originalSoknadRefId: String? = null,
        ettersendelser: List<String> = emptyList(),
    ): DigisosSak {
        val originalSoknad = originalSoknadRefId?.let {
            OriginalSoknadNAV(
                navEksternRefId = it,
                metadata = "",
                vedleggMetadata = "",
                soknadDokument = DokumentInfo("soknad.pdf", "doc-id", 100L),
                vedlegg = emptyList(),
                timestampSendt = 0L,
            )
        }
        val ettersendtInfo = if (ettersendelser.isNotEmpty()) {
            EttersendtInfoNAV(
                ettersendelser = ettersendelser.map {
                    Ettersendelse(
                        navEksternRefId = it,
                        vedleggMetadata = "",
                        vedlegg = emptyList(),
                        timestampSendt = 0L,
                    )
                },
            )
        } else null

        return DigisosSak(
            fiksDigisosId = fiksDigisosId,
            sokerFnr = "12345678910",
            fiksOrgId = "org-id",
            kommunenummer = "0301",
            sistEndret = 0L,
            originalSoknadNAV = originalSoknad,
            ettersendtInfoNAV = ettersendtInfo,
            digisosSoker = null,
            tilleggsinformasjon = null,
        )
    }

    @Test
    fun `uses fiksDigisosId as base when no originalSoknad or ettersendelser`() {
        val sak = digisosSak(fiksDigisosId = "abc123")
        val result = lagNavEksternRefId(sak)
        assertEquals("abc1230001", result)
    }

    @Test
    fun `uses originalSoknadNavEksternRefId plus 0000 as base when no ettersendelser`() {
        val sak = digisosSak(originalSoknadRefId = "soknad-ref-id")
        val result = lagNavEksternRefId(sak)
        // base is "soknad-ref-id0000", suffix increments to 0001
        assertTrue(result.endsWith("0001"), "Expected suffix 0001, got $result")
    }

    @Test
    fun `increments counter from highest existing ettersendelse`() {
        val sak = digisosSak(
            originalSoknadRefId = "ref0000",
            ettersendelser = listOf("ref0000", "ref0003", "ref0001"),
        )
        val result = lagNavEksternRefId(sak)
        assertEquals("ref0004", result)
    }

    @Test
    fun `handles malformed suffix gracefully without throwing`() {
        // suffix is "ABCD" — not numeric, should use 0L as fallback
        val sak = digisosSak(ettersendelser = listOf("refABCD"))
        val result = lagNavEksternRefId(sak)
        // maxByOrNull picks "refABCD" (only element), toLongOrNull returns null -> 0L
        // lagIdSuffix("refABCD") -> 0L + 1 = 1 -> "0001"
        assertTrue(result.endsWith("0001"), "Expected suffix 0001 for malformed input, got $result")
    }

    @Test
    fun `lagIdSuffix increments correctly`() {
        assertEquals("0001", lagIdSuffix("base0000"))
        assertEquals("0010", lagIdSuffix("base0009"))
        assertEquals("0100", lagIdSuffix("base0099"))
        assertEquals("9999", lagIdSuffix("base9998"))
    }

    @Test
    fun `lagIdSuffix handles malformed suffix without throwing`() {
        // Should not throw NumberFormatException
        val result = lagIdSuffix("baseXXXX")
        assertEquals("0001", result)
    }
}
