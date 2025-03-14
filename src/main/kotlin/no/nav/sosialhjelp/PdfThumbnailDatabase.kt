package no.nav.sosialhjelp

import no.nav.sosialhjelp.schema.UploadTable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

class PdfThumbnailDatabase {
    fun setPageCount(
        uploadId: UUID,
        numPages: Int,
    ) = transaction {
        UploadTable.update({ UploadTable.id eq uploadId }) { it[UploadTable.numPages] = numPages }
        notifyChange(uploadId)
    }

    private fun notifyChange(uploadId: UUID) =
        TransactionManager.Companion
            .current()
            .connection
            .prepareStatement("NOTIFY $uploadId", false)
            .executeUpdate()
}
