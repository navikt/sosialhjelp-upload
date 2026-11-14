package no.nav.sosialhjelp.upload.action

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val OSLO = ZoneId.of("Europe/Oslo")
private val THREE_WEEKS = Duration.ofDays(21)

internal fun ettersendelsesfristExpired(
    sendt: Instant,
    now: Instant,
): Boolean {
    val lastDay = sendt.plus(THREE_WEEKS).atZone(OSLO).toLocalDate()
    val nextDay = lastDay.plusDays(1).atStartOfDay(OSLO).toInstant()
    return !now.isBefore(nextDay)
}

class KlageNotFoundException : Exception()
