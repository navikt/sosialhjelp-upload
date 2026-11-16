package no.nav.sosialhjelp.upload.action.fiks

import java.util.UUID

data class KlageNAV(
    val klageId: UUID? = null,
    val digisosId: UUID? = null,
    val sendtKvittering: SendtKvittering? = null,
    val trukket: Boolean? = null,
)

data class SendtKvittering(
    val sendtStatus: Status? = null,
    val statusListe: List<Status>? = null,
)

data class Status(
    val status: String? = null,
    val timestamp: Long? = null,
)
