package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object DocumentTable : UUIDTable() {
    val vedleggType = varchar("vedleggType", 255)
    val soknadId = uuid("soknadId")

    init {
        uniqueIndex(soknadId, vedleggType)
    }
}
