package no.nav.sosialhjelp.upload.status

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ktor.util.reflect.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json.Default
import kotlinx.serialization.serializer
import no.nav.sosialhjelp.upload.common.SoknadDocumentIdent
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.reactive.DocumentStatusChannelFactory
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)
    val dsl: DSLContext by application.dependencies
    val documentRepository: DocumentRepository by application.dependencies
    val documentStatusService: DocumentStatusService by application.dependencies

    fun fromParameters(parameters: Parameters) =
        SoknadDocumentIdent(
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
            dsl.transactionCoroutine(Dispatchers.IO) { documentRepository.getOrCreateDocument(it, fromParameters(call.parameters), personident) }

        DocumentNotificationListener(statusChannelFactory, documentId).use { emitter ->
            send(documentStatusService.getDocumentStatus(documentId))

            emitter
                .getDocumentUpdateFlow()
                .collect { send(documentStatusService.getDocumentStatus(documentId)) }
        }
    }
}
