package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.database.schema.PageTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.update
import java.io.File
import java.util.*

class PageRepository {
    suspend fun setFilename(
        uploadId: UUID,
        pageIndex: Int,
        thumbnailFile: File,
    ) = PageTable.update({ (PageTable.upload eq uploadId) and (PageTable.pageNumber eq pageIndex) }) {
        it[filename] = thumbnailFile.name
    }

    suspend fun createEmptyPage(
        uploadId: UUID,
        pageIndex: Int,
    ) = PageTable.insert {
        it[upload] = uploadId
        it[pageNumber] = pageIndex
    }

    fun getPagesForUpload(uploadId: EntityID<UUID>) =
        PageTable
            .select(PageTable.pageNumber, PageTable.filename)
            .where { PageTable.upload eq uploadId }
}
