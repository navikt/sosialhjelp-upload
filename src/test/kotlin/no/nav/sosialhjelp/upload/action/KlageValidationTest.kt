package no.nav.sosialhjelp.upload.action

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.KlageNAV
import no.nav.sosialhjelp.upload.action.fiks.SendtKvittering
import no.nav.sosialhjelp.upload.action.fiks.SendtStatus
import no.nav.sosialhjelp.upload.validation.SubmissionValidationCode
import no.nav.sosialhjelp.upload.validation.SubmissionValidationException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KlageValidationTest {
    @Test
    fun `rejects invalid complaints before accessing upload resources`() =
        runTest {
            val fiks = mockk<FiksClient>()
            val service =
                EttersendelseService(
                    fiks,
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    mockk(),
                    clock = Clock.fixed(Instant.parse("2026-04-07T00:00:00Z"), ZoneOffset.UTC),
                )
            val digisosId = UUID.randomUUID()
            val klageId = UUID.randomUUID()

            suspend fun submit() =
                service.upload(
                    Metadata("dok", "info"),
                    digisosId.toString(),
                    "token",
                    UUID.randomUUID(),
                    "ident",
                    klageId,
                )

            coEvery { fiks.getKlager(digisosId.toString(), "token") } returns emptyList()
            assertFailsWith<KlageNotFoundException> { submit() }

            coEvery { fiks.getKlager(digisosId.toString(), "token") } returns
                listOf(KlageNAV(klageId, UUID.randomUUID()))
            assertFailsWith<KlageNotFoundException> { submit() }

            coEvery { fiks.getKlager(digisosId.toString(), "token") } returns
                listOf(KlageNAV(klageId, digisosId, trukket = true))
            assertEquals(
                listOf(SubmissionValidationCode.KLAGE_TRUKKET),
                assertFailsWith<SubmissionValidationException> { submit() }.violations,
            )

            val sent = Instant.parse("2026-03-15T22:30:00Z").toEpochMilli()
            coEvery { fiks.getKlager(digisosId.toString(), "token") } returns
                listOf(KlageNAV(klageId, digisosId, SendtKvittering(listOf(SendtStatus("SENDT", sent)))))
            assertEquals(
                listOf(SubmissionValidationCode.KLAGE_ETTERSENDELSE_FRIST_UTLOPT),
                assertFailsWith<SubmissionValidationException> { submit() }.violations,
            )
            coVerify(exactly = 0) { fiks.uploadEttersendelse(any(), any(), any(), any(), any(), any()) }
        }
}
