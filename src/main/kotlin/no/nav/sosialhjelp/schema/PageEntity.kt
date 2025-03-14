package no.nav.sosialhjelp.schema

import no.nav.sosialhjelp.progress.PageStatusResponse
import org.jetbrains.exposed.dao.CompositeEntity
import org.jetbrains.exposed.dao.CompositeEntityClass
import org.jetbrains.exposed.dao.id.CompositeID
import org.jetbrains.exposed.dao.id.EntityID

class PageEntity(
    id: EntityID<CompositeID>,
) : CompositeEntity(id) {
    companion object : CompositeEntityClass<PageEntity>(PageTable)

    var uploadId by PageTable.uploadId
    var pageNumber by PageTable.pageNumber
    var filename by PageTable.filename
}

fun PageEntity.toPage() = PageStatusResponse(pageNumber.value, filename)
