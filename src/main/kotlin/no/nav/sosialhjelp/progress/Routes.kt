package no.nav.sosialhjelp.progress

import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.schema.*
import no.nav.sosialhjelp.schema.DocumentTable.soknadId
import no.nav.sosialhjelp.schema.DocumentTable.vedleggType
import org.jetbrains.exposed.dao.id.CompositeID

fun getDocumentStatus(documentIdent: DocumentIdent): DocumentStatusResponse {
    val documentId =
        CompositeID {
            it[soknadId] = documentIdent.soknadId
            it[vedleggType] = documentIdent.vedleggType
        }

    DocumentEntity.findById(documentId) ?: throw NotFoundException("Document does not exist")

    val uploadIds = UploadEntity.find { UploadTable.documentId eq documentId }.map { it.id }

    val uploadsWithPages =
        PageEntity
            .find { PageTable.uploadId inList uploadIds }
            .sortedBy { PageTable.pageNumber }
            .groupBy { it.uploadId.value }

    return DocumentStatusResponse(
        soknadId = documentIdent.soknadId.toString(),
        vedleggType = documentIdent.vedleggType,
        uploads =
            uploadIds.map { uploadId ->
                UploadStatusResponse(
                    id = uploadId.toString(),
                    pages = uploadsWithPages[uploadId].orEmpty().map { page -> PageStatusResponse.fromPage(page) },
                )
            },
    )
}

fun Route.configureProgressRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)

    sse("/status/{soknadId}/{vedleggType}", serialize = JsonSerializer) {
        val documentId = DocumentIdent.fromParameters(call.parameters)
        val channel = statusChannelFactory.create(documentId)

        send(getDocumentStatus(documentId))

        heartbeat()

        channel
            .getUpdatesAsFlow()
            .collect { send(getDocumentStatus(documentId)) }
    }
}
