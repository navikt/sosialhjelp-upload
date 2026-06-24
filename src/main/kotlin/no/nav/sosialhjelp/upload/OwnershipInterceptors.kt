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
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import org.jooq.DSLContext
import java.util.UUID

/** Set by [verifySubmissionOwnership] — the validated submission ID parsed from the path parameter. */
val VerifiedSubmissionId = AttributeKey<UUID>("VerifiedSubmissionId")

/**
 * Set by [no.nav.sosialhjelp.upload.tus.verifyUploadOwnership] or [verifySubmissionOwnership]
 * — the authenticated user's personident.
 */
val VerifiedPersonident = AttributeKey<String>("VerifiedPersonident")

private fun verifySubmissionOwnershipPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifySubmissionOwnership") {
        val submissionQueries: SubmissionQueries by application.dependencies

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

            val owned =
                withContext(ioDispatcher) {
                    submissionQueries.isOwnedByUser(submissionId, personident)
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

// ── TokenX interceptors ─────────────────────────────────────────────────────────────────────────
// Used by endpoints called from sosialhjelp-soknad-api via TokenX token exchange.
// The exchanged token carries the original user's personnummer in the `pid` claim.

private fun verifyNavEksternRefIdOwnershipByPidPlugin(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) =
    createRouteScopedPlugin("VerifyNavEksternRefIdOwnershipByPid") {
        val dsl: DSLContext by application.dependencies
        val submissionQueries: SubmissionQueries by application.dependencies

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

            val owned =
                withContext(ioDispatcher) {
                    dsl.transactionResult { tx ->
                        submissionQueries.isNavEksternRefIdOwnedByUser(tx, navEksternRefId, personident)
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
 * Route-scoped ownership interceptor for navEksternRefId endpoints called via TokenX.
 *
 * Reads the user's personnummer from the `pid` claim in the TokenX JWT and verifies
 * they own a submission with the given `{navEksternRefId}`.
 * On success, [VerifiedPersonident] is available in `call.attributes`.
 */
fun Route.verifyNavEksternRefIdOwnershipByPid(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) {
    install(verifyNavEksternRefIdOwnershipByPidPlugin(ioDispatcher))
}
