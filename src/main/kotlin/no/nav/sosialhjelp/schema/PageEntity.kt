package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.UUIDEntity
import org.jetbrains.exposed.dao.UUIDEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import java.util.*

class PageEntity(
    id: EntityID<UUID>,
) : UUIDEntity(id) {
    companion object : UUIDEntityClass<PageEntity>(PageTable)

    var upload by UploadEntity referencedOn PageTable.upload
    var pageNumber by PageTable.pageNumber
    var filename by PageTable.filename
}
