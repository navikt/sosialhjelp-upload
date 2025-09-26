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
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.fs.Storage
import org.jooq.DSLContext
import java.io.File
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
    val uploadRepository: UploadRepository by application.dependencies
    val documentRepository: DocumentRepository by application.dependencies
    val downstreamUploadService: DownstreamUploadService by application.dependencies
    val notificationService: DocumentNotificationService by application.dependencies
    val storage: Storage by application.dependencies
    val dsl: DSLContext by application.dependencies

    post("/document/{documentId}/submit") {
        val personIdent = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val documentId = call.parameters["documentId"]?.let { UUID.fromString(it) } ?: error("documentId is required")
        val input = call.receive<SubmitInput>()
        if (!documentRepository.isOwnedByUser(documentId, personIdent)) {
            return@post call.respondText("Access denied", status = HttpStatusCode.Forbidden)
        }

        val uploads =
            dsl.transactionResult { tx ->
                uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
            }

        val response =
            downstreamUploadService.upload(
                input.metadata,
                fiksDigisosId = input.fiksDigisosId!!,
                files =
                    uploads.filter { it.errors.isEmpty() }.map { upload ->
                        val file =
                            upload.convertedFilename?.let { storage.retrieve(it) } ?: storage.retrieve(upload.originalFilename!!)
                                ?: error("File not found")
                        val ext = File(upload.convertedFilename ?: upload.originalFilename!!).extension
                        Upload(file, upload.originalFilename!!, ext)
                    },
                call.request.header("Authorization")!!.removePrefix("Bearer "),
            )
        if (response.status.isSuccess()) {
            dsl.transactionResult { tx ->
                documentRepository.cleanup(tx, documentId)
                notificationService.notifyUpdate(documentId)
            }
            call.respond(HttpStatusCode.Created)
        }
    }
}
