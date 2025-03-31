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
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*
import kotlin.time.Duration.Companion.seconds

fun Route.configureStatusRoutes() {
    val statusChannelFactory = DocumentStatusChannelFactory(environment)
    val documentRepository = DocumentRepository()

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    sse("/status/{soknadId}/{vedleggType}", serialize = { typeInfo: TypeInfo, value: Any ->
        val serializer = Default.serializersModule.serializer(typeInfo.kotlinType!!)
        Default.encodeToString(serializer, value)
    }) {
        val principal = call.principal<JWTPrincipal>()

        heartbeat {
            period = 10.seconds
            event = ServerSentEvent("ドキドキ")
        }

        val documentId = transaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters)) }

        DocumentStatusEmitter(statusChannelFactory, documentId).use { emitter ->
            send(emitter.getDocumentStatus())

            emitter
                .getDocumentUpdateFlow()
                .collect { send(emitter.getDocumentStatus()) }
        }
    }
}
