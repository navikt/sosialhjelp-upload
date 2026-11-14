package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.action.fiks.KlageNAV
import no.nav.sosialhjelp.upload.action.fiks.SendtKvittering
import no.nav.sosialhjelp.upload.action.fiks.SendtStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KlageFristTest {
    @Test
    fun `uses earliest SENDT before other statuses, falling back when no SENDT exists`() {
        val statuses = listOf(SendtStatus("BEKREFTET", 1), SendtStatus("SENDT", 3), SendtStatus("SENDT", 2))
        assertEquals(
            Instant.ofEpochMilli(2),
            klageSendtTidspunkt(KlageNAV(sendtKvittering = SendtKvittering(statuses))),
        )
        assertEquals(
            Instant.ofEpochMilli(1),
            klageSendtTidspunkt(KlageNAV(sendtKvittering = SendtKvittering(listOf(SendtStatus("BEKREFTET", 1))))),
        )
        assertNull(klageSendtTidspunkt(KlageNAV(sendtKvittering = SendtKvittering(emptyList()))))
    }

    @Test
    fun `deadline includes whole Oslo day after 21 times 24 hours across daylight saving transitions`() {
        val cases =
            listOf(
                "2026-03-01T13:00:00Z" to "2026-03-22T23:00:00Z",
                "2026-03-15T22:30:00Z" to "2026-04-06T22:00:00Z",
                "2026-10-11T21:30:00Z" to "2026-11-01T23:00:00Z",
            )
        for ((sent, nextDay) in cases) {
            val boundary = Instant.parse(nextDay)
            assertFalse(klageFristUtlopt(Instant.parse(sent), boundary.minusNanos(1)), sent)
            assertTrue(klageFristUtlopt(Instant.parse(sent), boundary), sent)
        }
    }
}
