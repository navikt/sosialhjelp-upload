package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class DocumentEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<DocumentEntity>(DocumentTable)

    var vedleggType by DocumentTable.vedleggType
    var soknadId by DocumentTable.soknadId
}
