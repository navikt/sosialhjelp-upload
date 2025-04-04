package no.nav.sosialhjelp.status

import DocumentRepository
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import kotlinx.serialization.json.Json.Default
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.database.reactive.DocumentStatusChannelFactory
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)
    val documentRepository = DocumentRepository()
    val documentStatusService = DocumentStatusService()

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    sse("/status/{soknadId}/{vedleggType}", serialize = { typeInfo: TypeInfo, value: Any ->
        val serializer = Default.serializersModule.serializer(typeInfo.kotlinType!!)
        Default.encodeToString(serializer, value)
    }) {
        val personident = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")

        heartbeat {
            period = 10.seconds
            event = ServerSentEvent("""{"heartbeat": "ドキドキ"}""")
        }

        val documentId =
            newSuspendedTransaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters), personident) }

        DocumentNotificationListener(statusChannelFactory, documentId.value).use { emitter ->
            send(documentStatusService.getDocumentStatus(documentId))

            emitter
                .getDocumentUpdateFlow()
                .collect { send(documentStatusService.getDocumentStatus(documentId)) }
        }
    }
}
