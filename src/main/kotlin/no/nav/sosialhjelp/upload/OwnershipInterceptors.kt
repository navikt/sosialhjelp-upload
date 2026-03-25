package no.nav.sosialhjelp.upload

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.isHandled
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import org.jooq.DSLContext
import java.util.UUID

/** Set by [verifyUploadOwnership] — the validated upload ID parsed from the path parameter. */
val VerifiedUploadId = AttributeKey<UUID>("VerifiedUploadId")

/** Set by [verifySubmissionOwnership] — the validated submission ID parsed from the path parameter. */
val VerifiedSubmissionId = AttributeKey<UUID>("VerifiedSubmissionId")

/** Set by [verifyUploadOwnership] or [verifySubmissionOwnership] — the authenticated user's personident. */
val VerifiedPersonident = AttributeKey<String>("VerifiedPersonident")

private val VerifyUploadOwnershipPlugin = createRouteScopedPlugin("VerifyUploadOwnership") {
    val dsl: DSLContext by application.dependencies
    val uploadRepository: UploadRepository by application.dependencies

    on(AuthenticationChecked) { call ->
        if (call.isHandled) return@on

        val personident = call.principal<JWTPrincipal>()?.subject
        if (personident == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@on
        }

        val uploadId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (uploadId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@on
        }

        val owned = withContext(Dispatchers.IO) {
            dsl.transactionResult { tx -> uploadRepository.isOwnedByUser(tx, uploadId, personident) }
        }
        if (!owned) {
            // Return 404 rather than 403 to avoid leaking whether the resource exists.
            call.respond(HttpStatusCode.NotFound)
            return@on
        }

        call.attributes.put(VerifiedUploadId, uploadId)
        call.attributes.put(VerifiedPersonident, personident)
    }
}

private val VerifySubmissionOwnershipPlugin = createRouteScopedPlugin("VerifySubmissionOwnership") {
    val submissionRepository: SubmissionRepository by application.dependencies

    on(AuthenticationChecked) { call ->
        if (call.isHandled) return@on

        val personident = call.principal<JWTPrincipal>()?.subject
        if (personident == null) {
            call.respond(HttpStatusCode.Unauthorized)
            return@on
        }

        val submissionId = call.parameters["submissionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (submissionId == null) {
            call.respond(HttpStatusCode.NotFound)
            return@on
        }

        val owned = withContext(Dispatchers.IO) {
            submissionRepository.isOwnedByUser(submissionId, personident)
        }
        if (!owned) {
            call.respond(HttpStatusCode.NotFound)
            return@on
        }

        call.attributes.put(VerifiedSubmissionId, submissionId)
        call.attributes.put(VerifiedPersonident, personident)
    }
}

/**
 * Route-scoped ownership interceptor for upload resources.
 *
 * Install this on any route group that contains `{id}` path parameters referring to uploads:
 * ```
 * route("/{id}") {
 *     verifyUploadOwnership()
 *     head { ... }
 *     patch { ... }
 * }
 * ```
 *
 * Every route nested under the group will have ownership verified before the handler runs.
 * On success, [VerifiedUploadId] and [VerifiedPersonident] are available in `call.attributes`.
 * On failure the pipeline is short-circuited via `call.respond` (404/401); the handler never runs.
 */
fun Route.verifyUploadOwnership() {
    install(VerifyUploadOwnershipPlugin)
}

/**
 * Route-scoped ownership interceptor for submission resources.
 *
 * Install this on any route group that contains `{submissionId}` path parameters:
 * ```
 * route("/submission/{submissionId}") {
 *     verifySubmissionOwnership()
 *     post("submit") { ... }
 * }
 * ```
 *
 * On success, [VerifiedSubmissionId] and [VerifiedPersonident] are available in `call.attributes`.
 * On failure the pipeline is short-circuited via `call.respond` (404/401); the handler never runs.
 */
fun Route.verifySubmissionOwnership() {
    install(VerifySubmissionOwnershipPlugin)
}
