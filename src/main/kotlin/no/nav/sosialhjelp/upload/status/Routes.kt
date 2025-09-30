package no.nav.sosialhjelp.upload.status

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json.Default
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import org.jooq.DSLContext
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory: DocumentNotificationService by application.dependencies
    val dsl: DSLContext by application.dependencies
    val documentRepository: DocumentRepository by application.dependencies
    val documentStatusService: DocumentStatusService by application.dependencies

    sse("/status/{id}", serialize = { typeInfo: TypeInfo, value: Any ->
        val serializer = Default.serializersModule.serializer(typeInfo.kotlinType!!)
        Default.encodeToString(serializer, value)
    }) {
        val personident = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")

        heartbeat {
            period = 10.seconds
            event = ServerSentEvent("""{"heartbeat": "ドキドキ"}""")
        }

        val documentId =
            dsl.transactionResult { config ->
                documentRepository.getOrCreateDocument(config, call.parameters["id"] ?: "", personident)
            }

        send(documentStatusService.getDocumentStatus(documentId))

        statusChannelFactory.getDocumentFlow(documentId).collect {
            send(documentStatusService.getDocumentStatus(documentId))
        }
    }
}
