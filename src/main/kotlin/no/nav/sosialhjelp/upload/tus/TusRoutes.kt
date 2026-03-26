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
import no.nav.sosialhjelp.upload.VerifiedUploadId
import no.nav.sosialhjelp.upload.database.UploadRepository.OffsetMismatchException
import no.nav.sosialhjelp.upload.tus.TusUploadService.UploadForbiddenException
import no.nav.sosialhjelp.upload.verifyUploadOwnership
import java.util.Base64

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
        val filename = metadata["filename"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Mangler filename")
        val contextId = metadata["contextId"] ?: return@post call.respond(HttpStatusCode.BadRequest, "Mangler contextId")

        val uploadId =
            try {
                tusUploadService.create(contextId, filename, uploadLength, personident)
            } catch (_: UploadForbiddenException) {
                return@post call.respond(HttpStatusCode.Forbidden)
            }

        call.response.header("Location", "$ProxyPath$basePath/$uploadId")
        call.response.header("Tus-Resumable", TUS_RESUMABLE)
        call.respond(HttpStatusCode.Created)
    }

    route("/{id}") {
        // Ownership is verified here for all routes in this group.
        // VerifiedUploadId and VerifiedPersonident are available in call.attributes after this runs.
        verifyUploadOwnership()

        // Get current upload offset (resume support)
        head {
            val uploadId = call.attributes[VerifiedUploadId]

            val (offset, size) =
                runCatching { tusUploadService.getUploadInfo(uploadId) }.getOrElse {
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
            val uploadId = call.attributes[VerifiedUploadId]

            val contentType = call.request.header("Content-Type")
            if (contentType != "application/offset+octet-stream") {
                return@patch call.respond(HttpStatusCode.UnsupportedMediaType)
            }
            val uploadOffset = call.request.header("Upload-Offset")?.toLongOrNull()
                ?: return@patch call.respond(HttpStatusCode.BadRequest)

            val userToken = call.request.header("Authorization")?.removePrefix("Bearer ")
                ?: return@patch call.respond(HttpStatusCode.Unauthorized)

            val data = call.receiveChannel().readRemaining(MAX_CHUNK_SIZE.toLong() + 1).readByteArray()
            if (data.size > MAX_CHUNK_SIZE) {
                return@patch call.respond(HttpStatusCode.PayloadTooLarge)
            }

            val newOffset =
                runCatching {
                    tusUploadService.appendChunk(uploadId, uploadOffset, data, userToken)
                }.getOrElse { e ->
                    when (e) {
                        is OffsetMismatchException -> return@patch call.respond(HttpStatusCode.Conflict)
                        else -> {
                            environment.log.error("Error appending chunk to upload $uploadId", e)
                            return@patch call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                }

            call.response.header("Upload-Offset", newOffset.toString())
            call.response.header("Tus-Resumable", TUS_RESUMABLE)
            call.respond(HttpStatusCode.NoContent)
        }

        // Terminate an upload
        delete {
            val uploadId = call.attributes[VerifiedUploadId]
            val userToken = call.request.header("Authorization")?.removePrefix("Bearer ")
                ?: return@delete call.respond(HttpStatusCode.Unauthorized)

            runCatching { tusUploadService.delete(uploadId, userToken) }.getOrElse {
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
            when (parts.size) {
                2 -> {
                    val key = parts[0].trim()
                    val value = runCatching { String(Base64.getDecoder().decode(parts[1].trim())) }.getOrNull() ?: return@mapNotNull null
                    key to value
                }

                1 -> {
                    parts[0].trim() to ""
                }

                else -> {
                    null
                }
            }
        }.toMap()
}


