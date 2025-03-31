package no.nav.sosialhjelp.database.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object DocumentTable : UUIDTable("documents") {
    val vedleggType = varchar("vedlegg_type", 255)
    val soknadId = uuid("soknad_id")
    val ownerIdent = char("owner_ident", 11)

    init {
        uniqueIndex(soknadId, vedleggType)
    }
}
