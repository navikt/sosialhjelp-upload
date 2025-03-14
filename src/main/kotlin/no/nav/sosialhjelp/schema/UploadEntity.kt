package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class UploadEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UploadEntity>(UploadTable)

    var document by DocumentEntity referencedOn UploadTable.document
    var originalFilename by UploadTable.originalFilename
}
