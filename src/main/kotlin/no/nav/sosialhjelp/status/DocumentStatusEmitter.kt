package no.nav.sosialhjelp.status

import io.ktor.utils.io.core.*
import io.r2dbc.postgresql.api.Notification
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.reactive.*
import no.nav.sosialhjelp.database.reactive.DocumentStatusChannelFactory
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.database.schema.UploadTable
import no.nav.sosialhjelp.status.dto.DocumentState
import no.nav.sosialhjelp.status.dto.PageState
import no.nav.sosialhjelp.status.dto.UploadSuccessState
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

class DocumentStatusEmitter(
    channelFactory: DocumentStatusChannelFactory,
    private val documentId: EntityID<UUID>,
) : Closeable {
    private val channel = channelFactory.create(documentId)

    fun getDocumentStatus(): DocumentState =
        transaction {
            val uploads =
                UploadTable
                    .select(UploadTable.id, UploadTable.originalFilename)
                    .where { UploadTable.document eq documentId }
                    .map { Pair(it[UploadTable.id], it[UploadTable.originalFilename]) }

            val pages =
                uploads.associate { (uploadId, _) ->
                    Pair(
                        uploadId,
                        PageTable
                            .select(PageTable.pageNumber, PageTable.filename)
                            .where { PageTable.upload eq uploadId }
                            .map {
                                PageState(
                                    pageNumber = it[PageTable.pageNumber],
                                    thumbnail = it[PageTable.filename],
                                )
                            },
                    )
                }

            DocumentState(
                documentId = documentId.toString(),
                uploads =
                    uploads.associate { (upload, originalFilename) ->
                        Pair(upload.toString(), UploadSuccessState(originalFilename, pages[upload]))
                    },
            )
        }

    override fun close() {
        channel.close()
    }

    fun getDocumentUpdateFlow(): Flow<Notification> =
        channel
            .getUpdatesAsFlux()
            .asFlow()
}
