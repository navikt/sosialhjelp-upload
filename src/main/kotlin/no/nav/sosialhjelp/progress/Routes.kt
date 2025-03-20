package no.nav.sosialhjelp.progress

import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import kotlinx.serialization.json.Json.Default
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.schema.DocumentTable
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun Route.configureProgressRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    sse("/status/{soknadId}/{vedleggType}", serialize = { typeInfo: TypeInfo, value: Any ->
        val serializer = Default.serializersModule.serializer(typeInfo.kotlinType!!)
        Default.encodeToString(serializer, value)
    }) {
        heartbeat {
            period = 10.seconds
            event = ServerSentEvent("heartbeat")
        }

        val documentId = transaction { DocumentTable.getOrCreateDocument(fromParameters(call.parameters)) }

        DocumentStatusEmitter(statusChannelFactory, documentId).use { emitter ->
            send(emitter.getDocumentStatus())

            emitter
                .getDocumentUpdateFlow()
                .collect { send(emitter.getDocumentStatus()) }
        }
    }
}
