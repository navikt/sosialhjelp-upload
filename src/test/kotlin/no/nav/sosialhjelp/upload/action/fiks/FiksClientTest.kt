package no.nav.sosialhjelp.upload.action.fiks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.api.fiks.DokumentInfo
import no.nav.sosialhjelp.api.fiks.ErrorMessage
import no.nav.sosialhjelp.api.fiks.Ettersendelse
import no.nav.sosialhjelp.api.fiks.EttersendtInfoNAV
import no.nav.sosialhjelp.api.fiks.OriginalSoknadNAV
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.texas.TexasClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FiksClientTest {
    private lateinit var fiksServer: MockWebServer
    private lateinit var texasServer: MockWebServer
    private lateinit var texasClient: TexasClient
    private lateinit var fiksClient: FiksClient

    @BeforeEach
    fun setUp() {
        fiksServer = MockWebServer()
        texasServer = MockWebServer()
        fiksServer.start()
        texasServer.start()
        texasClient = TexasClient(texasServer.url("/token").toString())
        fiksClient =
            FiksClient(
                fiksBaseUrl = fiksServer.url("/").toString().removeSuffix("/"),
                integrasjonsid = "integration-id",
                integrasjonspassord = "integration-password",
                texasClient = texasClient,
            )
    }

    @AfterEach
    fun tearDown() {
        texasClient.client.close()
        fiksServer.close()
        texasServer.close()
    }

    private fun enqueueTokenAndSuccessResponse() {
        texasServer.enqueue(
            MockResponse()
                .newBuilder()
                .code(HttpStatusCode.OK.value)
                .addHeader("Content-Type", "application/json")
                .body("""{"access_token":"maskinporten-token","expires_in":3600,"token_type":"Bearer"}""")
                .build(),
        )
        fiksServer.enqueue(MockResponse().newBuilder().code(HttpStatusCode.Created.value).build())
    }

    private fun assertVedleggJson(
        requestBody: String,
        klageId: String?,
    ) {
        val jsonPart = requestBody.substringAfter("\r\n\r\n").substringBefore("\r\n--")
        val vedlegg = jacksonObjectMapper().readTree(jsonPart).path("vedlegg")[0]
        assertEquals("dok", vedlegg.path("type").asText())
        assertEquals("info", vedlegg.path("tilleggsinfo").asText())
        if (klageId == null) {
            assertTrue(vedlegg.path("klageId").isMissingNode)
        } else {
            assertEquals(klageId, vedlegg.path("klageId").asText())
        }
        assertEquals("vedlegg.pdf", vedlegg.path("filer")[0].path("filnavn").asText())
    }

    @Test
    fun `uploads complaint ettersendelse to complaint endpoint with klageId in multipart JSON`() =
        runTest {
            val fiksDigisosId = UUID.randomUUID().toString()
            val navEksternRefId = UUID.randomUUID().toString()
            val klageId = UUID.randomUUID()
            enqueueTokenAndSuccessResponse()

            val response =
                fiksClient.uploadEttersendelse(
                    fiksDigisosId,
                    navEksternRefId,
                    EttersendelseMottaker.Klage(klageId),
                    Metadata("dok", "info"),
                    "user-token",
                    listOf(Fil("vedlegg.pdf", "checksum")),
                )

            assertEquals(HttpStatusCode.Created, response.status)
            val request = fiksServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/digisos/klage/api/v1/$fiksDigisosId/$navEksternRefId/$klageId/vedlegg", request.target)
            assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
            assertVedleggJson(request.body!!.utf8(), klageId.toString())
        }

    @Test
    fun `uploads application ettersendelse to existing endpoint with unchanged multipart JSON`() =
        runTest {
            val fiksDigisosId = UUID.randomUUID().toString()
            val navEksternRefId = "application-ref-0001"
            enqueueTokenAndSuccessResponse()

            val response =
                fiksClient.uploadEttersendelse(
                    fiksDigisosId,
                    navEksternRefId,
                    EttersendelseMottaker.Soknad("0301"),
                    Metadata("dok", "info"),
                    "user-token",
                    listOf(Fil("vedlegg.pdf", "checksum")),
                )

            assertEquals(HttpStatusCode.Created, response.status)
            val request = fiksServer.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/digisos/api/v2/soknader/0301/$fiksDigisosId/$navEksternRefId", request.target)
            assertTrue(request.headers["Content-Type"]!!.startsWith("multipart/form-data"))
            assertVedleggJson(request.body!!.utf8(), null)
        }

    private fun digisosSak(
        fiksDigisosId: String = "fiks-id",
        originalSoknadRefId: String? = null,
        ettersendelser: List<String> = emptyList(),
    ): DigisosSak {
        val originalSoknad =
            originalSoknadRefId?.let {
                OriginalSoknadNAV(
                    navEksternRefId = it,
                    metadata = "",
                    vedleggMetadata = "",
                    soknadDokument = DokumentInfo("soknad.pdf", "doc-id", 100L),
                    vedlegg = emptyList(),
                    timestampSendt = 0L,
                )
            }
        val ettersendtInfo =
            if (ettersendelser.isNotEmpty()) {
                EttersendtInfoNAV(
                    ettersendelser =
                        ettersendelser.map {
                            Ettersendelse(
                                navEksternRefId = it,
                                vedleggMetadata = "",
                                vedlegg = emptyList(),
                                timestampSendt = 0L,
                            )
                        },
                )
            } else {
                null
            }

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
        val sak =
            digisosSak(
                originalSoknadRefId = "ref0000",
                ettersendelser = listOf("ref0000", "ref0003", "ref0001"),
            )
        val result = lagNavEksternRefId(sak)
        assertEquals("ref0004", result)
    }

    @Test
    fun `ignores klage refs in Fiks ettersendelser when incrementing`() {
        val sak =
            digisosSak(
                originalSoknadRefId = "ref0000",
                ettersendelser = listOf("ref0002", "3f2a1b4c-0000-4000-8000-000000009999"),
            )
        assertEquals("ref0003", lagNavEksternRefId(sak))
    }

    @Test
    fun `isSoknadEttersendelseRef accepts counter refs and rejects other formats`() {
        assertTrue(isSoknadEttersendelseRef("fiks-id-0001"))
        assertTrue(isSoknadEttersendelseRef("ref0042"))
        kotlin.test.assertFalse(isSoknadEttersendelseRef("3f2a1b4c-0000-4000-8000-000000009999"))
        kotlin.test.assertFalse(isSoknadEttersendelseRef("3f2a1b4c-0000-4000-8000-00000000abcd"))
        kotlin.test.assertFalse(isSoknadEttersendelseRef("refABCD"))
        kotlin.test.assertFalse(isSoknadEttersendelseRef("0001"))
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

    @Test
    fun `sanitizes filenames from Fiks upload error`() {
        val errorMessage =
            createErrorMessage(
                errorId = "error-id",
                message =
                    "Opplastede filer stemmer ikke med metadata i vedlegg.json\n" +
                        "2 vedlegg listet i vedlegg.json er ikke lastet opp:\n" +
                        "  vedtak om godkjent flytting.pdf\n" +
                        "1 opplastede filer er ikke listet i vedlegg.json:\n" +
                        "  ettersendelse.pdf",
            )
        assertEquals(
            "errorId=error-id, message=Opplastede filer stemmer ikke med metadata i vedlegg.json | " +
                "2 vedlegg listet i vedlegg.json er ikke lastet opp: | " +
                "1 opplastede filer er ikke listet i vedlegg.json:",
            sanitizeFiksError(errorMessage),
        )
    }
}

fun createErrorMessage(
    errorId: String,
    message: String,
): ErrorMessage =
    ErrorMessage(
        errorId = errorId,
        message = message,
        error = null,
        errorCode = null,
        path = null,
        originalPath = null,
        errorJson = null,
        status = null,
        timestamp = null,
    )
