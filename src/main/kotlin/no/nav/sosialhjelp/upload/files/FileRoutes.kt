@file:Suppress("ReturnCount", "CyclomaticComplexMethod")

package no.nav.sosialhjelp.upload.files

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.application
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.common.toUploadMetadata
import no.nav.sosialhjelp.upload.status.dto.UploadDto
import no.nav.sosialhjelp.upload.status.dto.toDto
import no.nav.sosialhjelp.upload.validation.MAX_FILE_SIZE
import no.nav.sosialhjelp.upload.validation.ValidationCode
import java.io.IOException

private const val FORM_HEADROOM = 64L * 1024
private const val MAX_BODY = MAX_FILE_SIZE + FORM_HEADROOM

@Serializable
data class FileUploadResponse(
    val upload: UploadDto,
    val submissionId: String,
)

@Serializable
data class FileValidationResponse(
    val validations: List<ValidationCode>,
)

@Serializable
data class FileFailedResponse(
    val stage: String,
)

private sealed interface MultipartResult {
    class Ok(
        val fields: Map<String, String>,
        val filename: String,
        val data: ByteArray,
    ) : MultipartResult

    data object TooLarge : MultipartResult

    data object MultipleFiles : MultipartResult

    data object MissingFile : MultipartResult

    data object Malformed : MultipartResult
}

fun Route.configureFileRoutes() {
    post {
        val fileUploadService: FileUploadService by call.application.dependencies
        val personident =
            call.principal<JWTPrincipal>()?.subject ?: return@post call.respond(HttpStatusCode.Unauthorized)
        val token =
            call.request.header("Authorization")?.removePrefix("Bearer ")
                ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val declared = call.request.header("Content-Length")?.toLongOrNull()
        if (declared != null && declared > MAX_BODY) return@post tooLarge()

        val parsed =
            when (val result = parseMultipart()) {
                is MultipartResult.Ok -> result
                MultipartResult.TooLarge -> return@post tooLarge()
                MultipartResult.MultipleFiles ->
                    return@post call.respondText("Kun én fil per forespørsel", status = HttpStatusCode.BadRequest)
                MultipartResult.MissingFile ->
                    return@post call.respondText("Mangler filen", status = HttpStatusCode.BadRequest)
                MultipartResult.Malformed ->
                    return@post call.respondText("Ugyldig multipart", status = HttpStatusCode.BadRequest)
            }
        val metadata =
            (parsed.fields + ("filename" to parsed.filename)).toUploadMetadata().getOrElse {
                return@post call.respondText(it.message ?: "Ugyldig forespørsel", status = HttpStatusCode.BadRequest)
            }
        val correlationId =
            metadata.correlationId
                ?: return@post call.respondText("Mangler correlationId", status = HttpStatusCode.BadRequest)

        when (val result = fileUploadService.upload(metadata, correlationId, parsed.data, personident, token)) {
            is FileUploadResult.Stored -> {
                val dto = result.upload.toDto() ?: error("Stored upload has no id")
                call.respond(
                    if (result.replay) HttpStatusCode.OK else HttpStatusCode.Created,
                    FileUploadResponse(dto, result.submissionId.toString()),
                )
            }
            is FileUploadResult.Rejected ->
                call.respond(HttpStatusCode.UnprocessableEntity, FileValidationResponse(result.codes))
            is FileUploadResult.Failed ->
                call.respond(HttpStatusCode.BadGateway, FileFailedResponse(result.stage.name))
            FileUploadResult.Forbidden -> call.respond(HttpStatusCode.Forbidden)
        }
    }

    route("/{id}") {
        verifyUploadOwnership()
        delete {
            val fileUploadService: FileUploadService by call.application.dependencies
            fileUploadService.delete(call.attributes[VerifiedUploadId])
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun RoutingContext.tooLarge() =
    call.respondText("For stor forespørsel", status = HttpStatusCode.PayloadTooLarge)

private suspend fun RoutingContext.parseMultipart(): MultipartResult {
    val fields = mutableMapOf<String, String>()
    var filename: String? = null
    var data: ByteArray? = null
    var problem: MultipartResult? = null
    try {
        call.receiveMultipart(formFieldLimit = MAX_BODY).forEachPart { part ->
            try {
                // After the first problem, remaining parts are only disposed.
                if (problem != null) return@forEachPart
                when (part) {
                    is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                    is PartData.FileItem ->
                        if (data != null) {
                            problem = MultipartResult.MultipleFiles
                        } else {
                            val bytes = part.provider().readBuffer(MAX_BODY + 1).readByteArray()
                            if (bytes.size > MAX_BODY) {
                                problem = MultipartResult.TooLarge
                            } else {
                                filename = part.originalFileName
                                data = bytes
                            }
                        }
                    else -> Unit
                }
            } finally {
                part.release()
            }
        }
    } catch (_: IOException) {
        return MultipartResult.Malformed
    }
    problem?.let { return it }
    val name = filename
    val bytes = data
    return if (name == null || bytes == null) MultipartResult.MissingFile else MultipartResult.Ok(fields, name, bytes)
}
