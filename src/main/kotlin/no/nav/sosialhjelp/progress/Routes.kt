package no.nav.sosialhjelp.progress

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.reactive.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.progress.dto.DocumentState
import no.nav.sosialhjelp.progress.dto.PageState
import no.nav.sosialhjelp.progress.dto.UploadSuccessState
import no.nav.sosialhjelp.schema.DocumentTable
import no.nav.sosialhjelp.schema.PageTable
import no.nav.sosialhjelp.schema.UploadTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun getDocumentStatus(documentId: EntityID<UUID>): DocumentState =
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
                    PageTable.select(PageTable.pageNumber, PageTable.filename).where { PageTable.upload eq uploadId }.map {
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

val JsonSerializePlease = { typeInfo: TypeInfo, value: Any ->
    val serializer = Json.Default.serializersModule.serializer(typeInfo.kotlinType!!)
    Json.Default.encodeToString(serializer, value)
}

fun Route.configureProgressRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    sse("/status/{soknadId}/{vedleggType}", serialize = JsonSerializePlease) {
        val documentId = transaction { DocumentTable.getOrCreateDocument(fromParameters(call.parameters)) }
        val channel = statusChannelFactory.create(documentId)

        try {
            heartbeat {
                period = 10.seconds
                event = ServerSentEvent("heartbeat")
            }

            send(getDocumentStatus(documentId))

            channel
                .getUpdatesAsFlux()
                .asFlow()
                .collect {
                    println("Sending update to client")
                    send(getDocumentStatus(documentId))
                }
        } finally {
            channel.close()
        }
    }
}
