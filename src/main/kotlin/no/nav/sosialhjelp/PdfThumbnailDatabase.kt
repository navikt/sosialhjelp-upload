package no.nav.sosialhjelp

import no.nav.sosialhjelp.schema.PageEntity
import no.nav.sosialhjelp.schema.UploadEntity
import no.nav.sosialhjelp.schema.UploadTable
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

class PdfThumbnailDatabase {
    fun setPageCount(
        uploadId: UUID,
        numPages: Int,
    ) = transaction {
        for (i in 0..numPages - 1) {
            PageEntity.new {
                upload = UploadEntity[uploadId]
                pageNumber = i
            }
        }

        UploadTable.update({ UploadTable.id eq uploadId }) { it[UploadTable.numPages] = numPages }

        val documentId =
            UploadTable
                .select(UploadTable.document)
                .where { UploadTable.id eq uploadId }
                .first()

        exec("NOTIFY \"document::$documentId\"")
    }
}
