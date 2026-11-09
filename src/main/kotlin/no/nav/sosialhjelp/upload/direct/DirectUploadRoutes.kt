package no.nav.sosialhjelp.upload.direct

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import java.util.UUID

@Serializable
private data class InvalidUpload(
    val errors: List<String>,
)

@Serializable
private data class DocumentList(
    val documents: List<DirectDocument>,
)

@Serializable
private data class DraftRequest(
    val contextId: String,
    val fiksDigisosId: String,
)

@Serializable
private data class DirectSubmitRequest(
    val fiksDigisosId: String,
    val metadata: Metadata,
    val operationIds: List<String>,
)

@Serializable
private data class SubmitResult(
    val status: String,
)

@Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
fun Route.configureDirectUploadRoutes() {
    val service: DirectUploadService by application.dependencies

    post("/direct/drafts") {
        val owner = call.principal<JWTPrincipal>()?.subject ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val token =
            call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val input = call.receive<DraftRequest>()
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(service.createDraft(input.contextId, input.fiksDigisosId, owner, token))
    }

    post("/direct/submissions/{submissionId}/submit") {
        val principal = call.principal<JWTPrincipal>() ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val owner = principal.subject ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val applicationId =
            principal.payload.getClaim("client_id").asString()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val token =
            call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val id =
            call.parameters["submissionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val input = call.receive<DirectSubmitRequest>()
        val operations = input.operationIds.map { runCatching { UUID.fromString(it) }.getOrNull() }
        if (operations.any { it == null } || operations.isEmpty() || operations.distinct().size != operations.size) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(
            SubmitResult(
                service.submit(
                    id,
                    owner,
                    applicationId,
                    input.fiksDigisosId,
                    input.metadata,
                    operations.filterNotNull().toSet(),
                    token,
                ),
            ),
        )
    }

    get("/direct/submissions/{submissionId}/status") {
        val principal = call.principal<JWTPrincipal>() ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val owner = principal.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val applicationId =
            principal.payload.getClaim("client_id").asString()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val token =
            call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val id =
            call.parameters["submissionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        val caseId = call.request.queryParameters["fiksDigisosId"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(SubmitResult(service.submissionStatus(id, owner, applicationId, caseId, token)))
    }

    post("/direct/uploads") {
        val owner = call.principal<JWTPrincipal>()?.subject ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val callingApplication =
            call
                .principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("client_id")
                ?.asString()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val token =
            call.request.header(HttpHeaders.Authorization)?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val operationId =
            call.request
                .header("Upload-Operation-Id")
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val contextId =
            call.request.header("Upload-Context-Id")?.takeIf { it.isNotBlank() && it.length <= 2048 }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
        val reference = call.request.header("Upload-Nav-Ekstern-Ref-Id")
        val fiksId = call.request.header("Upload-Fiks-Digisos-Id")
        val category = call.request.header("Upload-Kategori")
        if ((reference == null && fiksId == null) ||
            (reference != null && !reference.matches(Regex("[A-Za-z0-9_-]{1,60}"))) ||
            (fiksId != null && runCatching { UUID.fromString(fiksId) }.isFailure) ||
            (category != null && (category.isBlank() || category.length > 255))
        ) {
            return@post call.respond(HttpStatusCode.BadRequest)
        }
        if (call.request.header(HttpHeaders.ContentType)?.startsWith("multipart/form-data", ignoreCase = true) !=
            true
        ) {
            return@post call.respond(HttpStatusCode.UnsupportedMediaType)
        }

        val multipart = call.receiveMultipart(formFieldLimit = (MAX_FILE_SIZE + 1).toLong())
        var filename: String? = null
        var bytes: ByteArray? = null
        while (true) {
            val part = multipart.readPart() ?: break
            try {
                if (part !is PartData.FileItem || part.name != "file" || bytes != null) {
                    return@post call.respond(HttpStatusCode.BadRequest)
                }
                filename = part.originalFileName?.takeIf { it.isNotBlank() && it.length <= 255 }
                    ?: return@post call.respond(HttpStatusCode.BadRequest)
                bytes = part.provider().readBuffer((MAX_FILE_SIZE + 1).toLong()).readByteArray()
                if (bytes.size > MAX_FILE_SIZE) return@post call.respond(HttpStatusCode.PayloadTooLarge)
            } finally {
                part.release()
            }
        }
        val input =
            DirectUploadInput(
                operationId,
                contextId,
                filename ?: return@post call.respond(HttpStatusCode.BadRequest),
                callingApplication,
                fiksId,
                reference,
                category,
                bytes ?: return@post call.respond(HttpStatusCode.BadRequest),
            )
        val result =
            try {
                service.upload(input, owner, token)
            } catch (_: DirectUploadConflict) {
                return@post call.respond(HttpStatusCode.Conflict)
            } catch (e: DirectUploadInvalid) {
                return@post call.respond(HttpStatusCode.UnprocessableEntity, InvalidUpload(e.codes))
            } catch (_: TusSubmissionQueries.SubmissionOwnedByAnotherUserException) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(if (result.status == "READY") HttpStatusCode.Created else HttpStatusCode.Accepted, result)
    }

    get("/direct/uploads/{operationId}") {
        val owner = call.principal<JWTPrincipal>()?.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val applicationId =
            call
                .principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("client_id")
                ?.asString()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val id =
            call.parameters["operationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        val document = service.status(id, owner, applicationId) ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(document)
    }

    delete("/direct/uploads/{operationId}") {
        val principal = call.principal<JWTPrincipal>() ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val owner = principal.subject ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val applicationId =
            principal.payload.getClaim("client_id").asString()
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
        val id =
            call.parameters["operationId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
        if (!service.delete(id, owner, applicationId)) return@delete call.respond(HttpStatusCode.NotFound)
        call.respond(HttpStatusCode.NoContent)
    }

    get("/direct/references/{navEksternRefId}/uploads") {
        val owner = call.principal<JWTPrincipal>()?.subject ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val applicationId =
            call
                .principal<JWTPrincipal>()
                ?.payload
                ?.getClaim("client_id")
                ?.asString()
                ?: return@get call.respond(HttpStatusCode.Unauthorized)
        val reference =
            call.parameters["navEksternRefId"]?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,60}")) }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respond(DocumentList(service.list(reference, owner, applicationId)))
    }
}
