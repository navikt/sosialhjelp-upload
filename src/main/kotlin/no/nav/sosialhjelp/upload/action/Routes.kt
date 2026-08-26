package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.VerifiedPersonident
import no.nav.sosialhjelp.upload.VerifiedSubmissionId
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.verifySubmissionOwnership

@Serializable
data class Metadata(
    val type: String,
    val tilleggsinfo: String,
    val innsendelsesfrist: String? = null,
    val hendelsetype: String? = null,
    val hendelsereferanse: String? = null,
)

@Serializable
data class SubmitInput(
    val fiksDigisosId: String? = null,
    val metadata: Metadata,
)

fun Route.configureActionRoutes() {
    val ettersendelseService: EttersendelseService by application.dependencies

    route("/submission/{submissionId}") {
        verifySubmissionOwnership()

        post("/submit") {
            val submissionId = call.attributes[VerifiedSubmissionId]
            val input = call.receive<SubmitInput>()
            val fiksDigisosId =
                input.fiksDigisosId
                    ?: return@post call.respondText("fiksDigisosId is required", status = HttpStatusCode.BadRequest)
            val token =
                call.request.header("Authorization")?.removePrefix("Bearer ")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val result =
                withMdc("fiksDigisosId" to fiksDigisosId) {
                    ettersendelseService.upload(
                        input.metadata,
                        fiksDigisosId = fiksDigisosId,
                        token,
                        submissionId,
                        personIdent = call.attributes[VerifiedPersonident],
                    )
                }
            if (result) {
                return@post call.respond(HttpStatusCode.Created)
            } else {
                return@post call.respond(HttpStatusCode.BadGateway, "Submission rejected by Fiks")
            }
        }
    }
}
