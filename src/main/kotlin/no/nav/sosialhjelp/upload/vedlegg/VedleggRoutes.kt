package no.nav.sosialhjelp.upload.vedlegg

import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.NoContent
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.sosialhjelp.upload.verifyNavEksternRefIdOwnershipByPid

fun Route.configureVedleggRoutes() {
    val vedleggService: VedleggService by application.dependencies

    route("/vedlegg") {
        // Called machine-to-machine by sosialhjelp-soknad-api; uses TokenX auth.
        authenticate("tokenx") {
            route("/{navEksternRefId}") {
                verifyNavEksternRefIdOwnershipByPid()

                // GET /vedlegg/{navEksternRefId} — return JsonVedleggSpesifikasjon for the given soknadId.
                get {
                    val navEksternRefId = call.parameters["navEksternRefId"]!!
                    val vedlegg = vedleggService.getVedleggByNavEksternRefId(navEksternRefId)
                    call.respond(vedlegg)
                }

                delete {
                    val navEksternRefId = call.parameters["navEksternRefId"]!!
                    vedleggService.deleteVedlegg(navEksternRefId)
                    call.respond(NoContent)
                }

                delete("/{kategori}") {
                    val navEksternRefId =
                        call.parameters["navEksternRefId"] ?: return@delete call.respondText(
                            "Missing navEksternRefId",
                            status = BadRequest,
                        )
                    val kategori =
                        call.parameters["kategori"] ?: return@delete call.respondText(
                            "Missing kategori (vedleggstype)",
                            status = BadRequest,
                        )

                    vedleggService.deleteVedlegg(navEksternRefId, kategori)
                    call.respond(NoContent)
                }
            }
        }
    }
}
