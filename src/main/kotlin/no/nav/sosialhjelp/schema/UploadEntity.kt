package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.UUID

class UploadEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<UploadEntity>(UploadTable)

    var originalFilename by UploadTable.originalFilename
    var numPages by UploadTable.numPages
    var documentId by DocumentEntity referencedOn UploadTable.documentId
}
