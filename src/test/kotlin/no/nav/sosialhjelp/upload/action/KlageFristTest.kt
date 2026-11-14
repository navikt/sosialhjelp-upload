package no.nav.sosialhjelp.upload.action

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KlageFristTest {
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
            assertFalse(ettersendelsesfristExpired(Instant.parse(sent), boundary.minusNanos(1)), sent)
            assertTrue(ettersendelsesfristExpired(Instant.parse(sent), boundary), sent)
        }
    }
}
