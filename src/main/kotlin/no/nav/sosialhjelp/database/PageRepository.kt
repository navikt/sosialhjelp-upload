package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.database.schema.PageTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import java.io.File
import java.util.*

class PageRepository {
    fun setFilename(
        uploadId: UUID,
        pageIndex: Int,
        thumbnailFile: File,
    ) = PageTable.update({ (PageTable.upload eq uploadId) and (PageTable.pageNumber eq pageIndex) }) {
        it[filename] = thumbnailFile.name
    }

    fun createEmptyPage(
        uploadId: UUID,
        pageIndex: Int,
    ) = PageTable.insert {
        it[upload] = uploadId
        it[pageNumber] = pageIndex
    }
}
