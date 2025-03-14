package no.nav.sosialhjelp

import no.nav.sosialhjelp.schema.PageEntity
import no.nav.sosialhjelp.schema.UploadEntity
import no.nav.sosialhjelp.schema.UploadTable
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.*

class PdfThumbnailDatabase {
    fun setPageCount(
        uploadId: UUID,
        numPages: Int,
    ): Int {
        transaction {
            for (i in 0..numPages - 1) {
                PageEntity.new {
                    upload = UploadEntity[uploadId]
                    pageNumber = i
                }
            }
        }
        return transaction {
            UploadTable.update({ UploadTable.id eq uploadId }) { it[UploadTable.numPages] = numPages }
            val document = UploadEntity.get(uploadId).document
            println("NOTIFY \"${document.soknadId}::${document.vedleggType}\"")
            notifyChange(document.soknadId, document.vedleggType)
        }
    }

    fun notifyChange(
        soknadId: UUID,
        vedleggType: String,
    ) = TransactionManager.Companion
        .current()
        .connection
        .prepareStatement("NOTIFY \"$soknadId::${vedleggType}\"", false)
        .executeUpdate()
}
