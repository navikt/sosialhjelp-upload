package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.EntityID

class DocumentEntity(
    id: EntityID<CompositeID>,
) : CompositeEntity(id) {
    companion object : CompositeEntityClass<DocumentEntity>(DocumentTable)

    var vedleggType by DocumentTable.vedleggType
    var soknadId by DocumentTable.soknadId
}
