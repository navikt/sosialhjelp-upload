package no.nav.sosialhjelp.upload.tus

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import no.nav.sosialhjelp.upload.tus.TusUploadService.UploadForbiddenException
import java.util.Base64
import java.util.UUID

private const val TUS_RESUMABLE = "1.0.0"
private const val TUS_VERSION = "1.0.0"
private const val TUS_EXTENSION = "creation,termination"
private const val MAX_CHUNK_SIZE = 10 * 1024 * 1024 + 1024 // 10MB + some headroom

private const val ProxyPath = "/sosialhjelp/innsyn/api/upload-api"

fun Route.configureTusRoutes(basePath: String) {
    val tusUploadService: TusUploadService by application.dependencies


    options {
        call.response.header("Tus-Resumable", TUS_RESUMABLE)
        call.response.header("Tus-Version", TUS_VERSION)
        call.response.header("Tus-Extension", TUS_EXTENSION)
        call.response.header("Tus-Max-Size", MAX_CHUNK_SIZE.toString())
        call.respond(HttpStatusCode.NoContent)
    }

    // Create a new upload
    post {
        val personident = call.principal<JWTPrincipal>()?.subject
            ?: return@post call.respond(HttpStatusCode.Unauthorized)

        val tusResumable = call.request.header("Tus-Resumable")
        if (tusResumable != TUS_RESUMABLE) {
            call.response.header("Tus-Version", TUS_VERSION)
            return@post call.respond(HttpStatusCode.PreconditionFailed)
        }

        val uploadLength = call.request.header("Upload-Length")?.toLongOrNull()
            ?: return@post call.respond(HttpStatusCode.BadRequest)

        val metadata = parseMetadata(call.request.header("Upload-Metadata"))
        val filename = metadata["filename"] ?: return@post call.respond(HttpStatusCode.BadRequest)
        val externalId = metadata["externalId"] ?: return@post call.respond(HttpStatusCode.BadRequest)

        val uploadId =
            try {
                tusUploadService.create(externalId, filename, uploadLength, personident)
            } catch (_: UploadForbiddenException) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

        call.response.header("Location", "$ProxyPath$basePath/$uploadId")
        call.response.header("Tus-Resumable", TUS_RESUMABLE)
        call.respond(HttpStatusCode.Created)
    }

    route("/{id}") {
        // Get current upload offset (resume support)
        head {
            val personident = call.principal<JWTPrincipal>()?.subject
                ?: return@head call.respond(HttpStatusCode.Unauthorized)
            val uploadId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@head call.respond(HttpStatusCode.NotFound)

            val (offset, size) =
                runCatching { tusUploadService.getUploadInfo(uploadId, personident) }.getOrElse {
                    return@head call.respond(HttpStatusCode.NotFound)
                }

            call.response.header("Upload-Offset", offset.toString())
            call.response.header("Upload-Length", size.toString())
            call.response.header("Tus-Resumable", TUS_RESUMABLE)
            call.response.header("Cache-Control", "no-store")
            call.respond(HttpStatusCode.OK)
        }

        // Append a chunk
        patch {
            val personident = call.principal<JWTPrincipal>()?.subject
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)
            val uploadId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@patch call.respond(HttpStatusCode.NotFound)

            val contentType = call.request.header("Content-Type")
            if (contentType != "application/offset+octet-stream") {
                return@patch call.respond(HttpStatusCode.UnsupportedMediaType)
            }
            val uploadOffset = call.request.header("Upload-Offset")?.toLongOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest)

            val userToken = call.request.header("Authorization")?.removePrefix("Bearer ")
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val data = call.receiveChannel().readRemaining().readByteArray()

            val newOffset =
                runCatching {
                    tusUploadService.appendChunk(uploadId, uploadOffset, data, personident, userToken)
                }.getOrElse { e ->
                    environment.log.error("Error appending chunk to upload $uploadId", e)
                    return@patch call.respond(HttpStatusCode.InternalServerError)
                }

            call.response.header("Upload-Offset", newOffset.toString())
            call.response.header("Tus-Resumable", TUS_RESUMABLE)
            call.respond(HttpStatusCode.NoContent)
        }

        // Terminate an upload
        delete {
            val personident = call.principal<JWTPrincipal>()?.subject
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)
            val uploadId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.NotFound)

            runCatching { tusUploadService.delete(uploadId, personident) }.getOrElse {
                return@delete call.respond(HttpStatusCode.Forbidden)
            }

            call.response.header("Tus-Resumable", TUS_RESUMABLE)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Parses the Upload-Metadata header.
 * Format: "key base64value, key base64value"
 */
private fun parseMetadata(header: String?): Map<String, String> {
    if (header.isNullOrBlank()) return emptyMap()
    return header
        .split(",")
        .mapNotNull { pair ->
            val parts = pair.trim().split(" ", limit = 2)
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = runCatching { String(Base64.getDecoder().decode(parts[1].trim())) }.getOrNull() ?: return@mapNotNull null
                key to value
            } else if (parts.size == 1) {
                parts[0].trim() to ""
            } else {
                null
            }
        }.toMap()
}
