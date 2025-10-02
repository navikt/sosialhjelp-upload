package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.database.DocumentRepository
import java.util.*

@Serializable
data class Metadata(
    val type: String,
    val tilleggsinfo: String? = null,
    val innsendelsesfrist: String? = null,
    val hendelsetype: String? = null,
    val hendelsereferanse: String? = null,
)

@Serializable
data class SubmitInput(
    val fiksDigisosId: String? = null,
    val metadata: Metadata,
    val mellomlagring: Boolean,
)

fun Route.configureActionRoutes() {
    val documentRepository: DocumentRepository by application.dependencies
    val downstreamUploadService: DownstreamUploadService by application.dependencies

    post("/document/{documentId}/submit") {
        val personIdent = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val documentId = call.parameters["documentId"]?.let { UUID.fromString(it) } ?: error("documentId is required")
        val input = call.receive<SubmitInput>()
        if (!documentRepository.isOwnedByUser(documentId, personIdent)) {
            return@post call.respondText("Access denied", status = HttpStatusCode.Forbidden)
        }

        val result =
            downstreamUploadService.upload(
                input.metadata,
                fiksDigisosId = input.fiksDigisosId!!,
                call.request.header("Authorization")!!.removePrefix("Bearer "),
                documentId,
            )
        if (result) {
            return@post call.respond(HttpStatusCode.Created)
        }
    }
}
