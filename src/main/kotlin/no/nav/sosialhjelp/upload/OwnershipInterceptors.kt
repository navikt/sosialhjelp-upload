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
import kotlinx.coroutines.CoroutineDispatcher
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

/** Set by [verifyVedleggUploadOwnership] — the validated uploadId from the {uploadId} path parameter. */
val VerifiedVedleggUploadId = AttributeKey<UUID>("VerifiedVedleggUploadId")

/** Set by [verifyUploadOwnership] or [verifySubmissionOwnership] — the authenticated user's personident. */
val VerifiedPersonident = AttributeKey<String>("VerifiedPersonident")

private fun verifyUploadOwnershipPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyUploadOwnership") {
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

            val owned = withContext(ioDispatcher) {
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

private fun verifySubmissionOwnershipPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifySubmissionOwnership") {
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

            val owned = withContext(ioDispatcher) {
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
fun Route.verifyUploadOwnership(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyUploadOwnershipPlugin(ioDispatcher))
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
fun Route.verifySubmissionOwnership(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifySubmissionOwnershipPlugin(ioDispatcher))
}

private fun verifyVedleggUploadOwnershipPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyVedleggUploadOwnership") {
        val dsl: DSLContext by application.dependencies
        val uploadRepository: UploadRepository by application.dependencies

        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on

            val personident = call.principal<JWTPrincipal>()?.subject
            if (personident == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@on
            }

            val uploadId = call.parameters["uploadId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (uploadId == null) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            val owned = withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.isOwnedByUser(tx, uploadId, personident) }
            }
            if (!owned) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            call.attributes.put(VerifiedVedleggUploadId, uploadId)
            call.attributes.put(VerifiedPersonident, personident)
        }
    }

private fun verifyNavEksternRefIdOwnershipPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyNavEksternRefIdOwnership") {
        val dsl: DSLContext by application.dependencies
        val submissionRepository: SubmissionRepository by application.dependencies

        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on

            val personident = call.principal<JWTPrincipal>()?.subject
            if (personident == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@on
            }

            val navEksternRefId = call.parameters["navEksternRefId"]
            if (navEksternRefId == null) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            val owned = withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    submissionRepository.isNavEksternRefIdOwnedByUser(tx, navEksternRefId, personident)
                }
            }
            if (!owned) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            call.attributes.put(VerifiedPersonident, personident)
        }
    }

/**
 * Route-scoped ownership interceptor for vedlegg upload endpoints.
 *
 * Reads `{uploadId}` from the path and verifies the authenticated user owns it.
 * On success, [VerifiedVedleggUploadId] and [VerifiedPersonident] are available in `call.attributes`.
 */
fun Route.verifyVedleggUploadOwnership(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyVedleggUploadOwnershipPlugin(ioDispatcher))
}

/**
 * Route-scoped ownership interceptor for navEksternRefId-scoped vedlegg endpoints.
 *
 * Reads `{navEksternRefId}` from the path and verifies the authenticated user owns a submission
 * with that reference ID. On success, [VerifiedPersonident] is available in `call.attributes`.
 */
fun Route.verifyNavEksternRefIdOwnership(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyNavEksternRefIdOwnershipPlugin(ioDispatcher))
}

// ── TokenX interceptors ─────────────────────────────────────────────────────────────────────────
// Used by endpoints called from sosialhjelp-soknad-api via TokenX token exchange.
// The exchanged token carries the original user's personnummer in the `pid` claim.

private fun verifyVedleggUploadOwnershipByPidPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyVedleggUploadOwnershipByPid") {
        val dsl: DSLContext by application.dependencies
        val uploadRepository: UploadRepository by application.dependencies

        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on

            val personident = call.principal<JWTPrincipal>()?.payload?.getClaim("pid")?.asString()
            if (personident == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@on
            }

            val uploadId = call.parameters["uploadId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (uploadId == null) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            val owned = withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.isOwnedByUser(tx, uploadId, personident) }
            }
            if (!owned) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            call.attributes.put(VerifiedVedleggUploadId, uploadId)
            call.attributes.put(VerifiedPersonident, personident)
        }
    }

private fun verifyNavEksternRefIdOwnershipByPidPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyNavEksternRefIdOwnershipByPid") {
        val dsl: DSLContext by application.dependencies
        val submissionRepository: SubmissionRepository by application.dependencies

        on(AuthenticationChecked) { call ->
            if (call.isHandled) return@on

            val personident = call.principal<JWTPrincipal>()?.payload?.getClaim("pid")?.asString()
            if (personident == null) {
                call.respond(HttpStatusCode.Unauthorized)
                return@on
            }

            val navEksternRefId = call.parameters["navEksternRefId"]
            if (navEksternRefId == null) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            val owned = withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    submissionRepository.isNavEksternRefIdOwnedByUser(tx, navEksternRefId, personident)
                }
            }
            if (!owned) {
                call.respond(HttpStatusCode.NotFound)
                return@on
            }

            call.attributes.put(VerifiedPersonident, personident)
        }
    }

/**
 * Route-scoped ownership interceptor for vedlegg upload endpoints called via TokenX.
 *
 * Reads the user's personnummer from the `pid` claim in the TokenX JWT and verifies
 * they own the upload identified by `{uploadId}`.
 * On success, [VerifiedVedleggUploadId] and [VerifiedPersonident] are available in `call.attributes`.
 */
fun Route.verifyVedleggUploadOwnershipByPid(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyVedleggUploadOwnershipByPidPlugin(ioDispatcher))
}

/**
 * Route-scoped ownership interceptor for navEksternRefId endpoints called via TokenX.
 *
 * Reads the user's personnummer from the `pid` claim in the TokenX JWT and verifies
 * they own a submission with the given `{navEksternRefId}`.
 * On success, [VerifiedPersonident] is available in `call.attributes`.
 */
fun Route.verifyNavEksternRefIdOwnershipByPid(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyNavEksternRefIdOwnershipByPidPlugin(ioDispatcher))
}
