package no.nav.sosialhjelp

import no.nav.sosialhjelp.schema.PageTable
import no.nav.sosialhjelp.schema.UploadTable
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class PdfThumbnailDatabase {
    private fun notifyChange(uploadId: UUID) =
        transaction {
            val documentId = UploadTable.select(UploadTable.document).where { UploadTable.id eq uploadId }.first()
            exec("NOTIFY \"document::$documentId\"")
        }

    fun setPageCount(
        uploadId: UUID,
        numPages: Int,
    ) {
        transaction {
            PageTable.batchInsert(0..numPages - 1, shouldReturnGeneratedValues = false) {
                this[PageTable.upload] = uploadId
                this[PageTable.pageNumber] = it
            }
        }
        notifyChange(uploadId)
    }
}
