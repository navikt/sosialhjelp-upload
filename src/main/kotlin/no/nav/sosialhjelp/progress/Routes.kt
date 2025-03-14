package no.nav.sosialhjelp.progress

import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.schema.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction

fun getDocumentStatus(documentIdent: DocumentIdent): DocumentStatusResponse {
    val uploadsWithPages =
        transaction {
            val document =
                DocumentEntity
                    .find {
                        (DocumentTable.soknadId eq documentIdent.soknadId) and (DocumentTable.vedleggType eq documentIdent.vedleggType)
                    }.firstOrNull()
                    ?: throw NotFoundException("Document does not exist")

            val uploadIds =
                UploadEntity
                    .find { UploadTable.document eq document.id }
                    .map { it.id }

            PageEntity
                .find { PageTable.upload inList uploadIds }
                .sortedBy { PageTable.pageNumber }
                .groupBy { PageTable.upload }
        }

    return DocumentStatusResponse(
        soknadId = documentIdent.soknadId.toString(),
        vedleggType = documentIdent.vedleggType,
        uploads =
            uploadsWithPages.map { (uploadId, pages) ->
                UploadStatusResponse(
                    id = uploadId.toString(),
                    pages = pages.map { page -> PageStatusResponse.fromPageEntity(page) },
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
