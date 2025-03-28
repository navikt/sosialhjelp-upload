package no.nav.sosialhjelp.database.schema

import no.nav.sosialhjelp.common.DocumentIdent
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import java.util.*

object DocumentTable : UUIDTable("document") {
    val vedleggType = varchar("vedleggType", 255)
    val soknadId = uuid("soknadId")

    init {
        uniqueIndex(soknadId, vedleggType)
    }

    fun getOrCreateDocument(documentIdent: DocumentIdent): EntityID<UUID> =
        DocumentTable
            .insertIgnoreAndGetId {
                it[soknadId] = documentIdent.soknadId
                it[vedleggType] = documentIdent.vedleggType
            } ?: DocumentTable
            .select(id)
            .where {
                (
                    soknadId eq documentIdent.soknadId
                ) and (
                    vedleggType eq documentIdent.vedleggType
                )
            }.map { it[id] }
            .first()
}
