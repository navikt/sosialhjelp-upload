package no.nav.sosialhjelp.upload.vedlegg

import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import no.nav.sosialhjelp.upload.verifyNavEksternRefIdOwnershipByPid

fun Route.configureVedleggRoutes() {
    val vedleggService: VedleggService by application.dependencies

    route("/vedlegg") {
        // GET /vedlegg/{navEksternRefId} — return JsonVedleggSpesifikasjon for the given soknadId.
        // Called machine-to-machine by sosialhjelp-soknad-api; uses TokenX auth.
        authenticate("tokenx") {
            route("/{navEksternRefId}") {
                verifyNavEksternRefIdOwnershipByPid()

                get {
                    val navEksternRefId = call.parameters["navEksternRefId"]!!
                    val vedlegg = vedleggService.getVedleggByNavEksternRefId(navEksternRefId)
                    call.respond(vedlegg)
                }
            }
        }
    }
}
