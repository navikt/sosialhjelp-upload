package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.action.fiks.KlageNAV
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

private val OSLO = ZoneId.of("Europe/Oslo")
private val THREE_WEEKS = Duration.ofDays(21)

internal fun klageSendtTidspunkt(klage: KlageNAV): Instant? {
    val statuses = klage.sendtKvittering?.statusListe.orEmpty()
    val sent = statuses.filter { it.status == "SENDT" }.mapNotNull { it.timestamp }
    val timestamp = sent.minOrNull() ?: statuses.mapNotNull { it.timestamp }.minOrNull()
    return timestamp?.let(Instant::ofEpochMilli)
}

internal fun klageFristUtlopt(
    sendt: Instant,
    now: Instant,
): Boolean {
    val lastDay = sendt.plus(THREE_WEEKS).atZone(OSLO).toLocalDate()
    val nextDay = lastDay.plusDays(1).atStartOfDay(OSLO).toInstant()
    return !now.isBefore(nextDay)
}

class KlageNotFoundException : Exception()
