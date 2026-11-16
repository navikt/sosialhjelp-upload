package no.nav.sosialhjelp.upload.action

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val OSLO = ZoneId.of("Europe/Oslo")
private val KLAGE_FRIST = Duration.ofDays(90)

internal fun ettersendelsesfristExpired(
    sendt: Instant,
    now: Instant,
): Boolean {
    val lastDay = sendt.plus(KLAGE_FRIST).atZone(OSLO).toLocalDate()
    val nextDay = lastDay.plusDays(1).atStartOfDay(OSLO).toInstant()
    return !now.isBefore(nextDay)
}

class KlageNotFoundException : Exception()
