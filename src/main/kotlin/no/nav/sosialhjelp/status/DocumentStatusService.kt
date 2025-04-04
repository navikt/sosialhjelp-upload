package no.nav.sosialhjelp.status

import no.nav.sosialhjelp.database.PageRepository
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.status.dto.DocumentState
import no.nav.sosialhjelp.status.dto.PageState
import no.nav.sosialhjelp.status.dto.UploadSuccessState
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Query
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*

class DocumentStatusService {
    val uploadRepository = UploadRepository()
    val pageRepository = PageRepository()

    private fun Query.mapToPageState() = map { PageState(it[PageTable.pageNumber], it[PageTable.filename]) }

    suspend fun getDocumentStatus(documentId: EntityID<UUID>): DocumentState =
        newSuspendedTransaction {
            val filenamesByUpload = uploadRepository.getUploadsWithFilenames(documentId)
            val pageStatesByUpload = filenamesByUpload.associate { it.first to pageRepository.getPagesForUpload(it.first).mapToPageState() }

            DocumentState.from(
                documentId.toString(),
                filenamesByUpload.associate { (uploadId, originalFilename) ->
                    uploadId to
                        UploadSuccessState(originalFilename, pageStatesByUpload[uploadId])
                },
            )
        }
}
