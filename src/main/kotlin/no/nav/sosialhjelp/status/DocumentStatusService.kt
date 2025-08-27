package no.nav.sosialhjelp.status

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import no.nav.sosialhjelp.database.PageRepository
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.status.dto.DocumentState
import no.nav.sosialhjelp.status.dto.PageState
import no.nav.sosialhjelp.status.dto.UploadSuccessState
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.r2dbc.Query
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.*

class DocumentStatusService {
    val uploadRepository = UploadRepository()
    val pageRepository = PageRepository()

    private fun Query.mapToPageState() = map { PageState(it[PageTable.pageNumber], it[PageTable.filename]) }

    suspend fun getDocumentStatus(documentId: EntityID<UUID>): DocumentState =
        suspendTransaction(Dispatchers.IO) {
            val filenamesByUpload = uploadRepository.getUploadsWithFilenames(documentId)
            val pageStatesByUpload = filenamesByUpload.keys.associateWith { pageRepository.getPagesForUpload(it).mapToPageState().toList() }

            val uploads = filenamesByUpload.mapValues { (uploadId, originalFilename) ->
                UploadSuccessState(originalFilename, pageStatesByUpload[uploadId])
            }
            DocumentState.from(
                documentId.toString(),
                uploads,
            )
        }
}
