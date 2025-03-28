package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
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

    fun setPageCount(
        uploadId: UUID,
        numPages: Int,
    ) = transaction {
        for (i in 0..numPages - 1) {
            PageTable.insert {
                it[upload] = uploadId
                it[pageNumber] = i
            }
        }

        val documentId =
            UploadTable
                .select(UploadTable.document)
                .where { UploadTable.id eq uploadId }
                .first()

        exec("NOTIFY \"document::$documentId\"")
    }
}
