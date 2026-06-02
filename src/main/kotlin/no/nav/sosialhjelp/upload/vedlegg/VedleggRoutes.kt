package no.nav.sosialhjelp.upload.vedlegg

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import no.nav.sosialhjelp.upload.VerifiedVedleggUploadId
import no.nav.sosialhjelp.upload.verifyNavEksternRefIdOwnershipByPid
import no.nav.sosialhjelp.upload.verifyVedleggUploadOwnership

fun Route.configureVedleggRoutes() {
    val vedleggService: VedleggService by application.dependencies

    route("/vedlegg") {
        // POST /vedlegg/{uploadId}/type — set document type on a completed upload.
        // Called machine-to-machine by sosialhjelp-soknad-api; uses TokenX auth.
        authenticate("idporten") {
            route("/{uploadId}/kategori") {
                verifyVedleggUploadOwnership()
                patch {
                    val uploadId = call.attributes[VerifiedVedleggUploadId]
                    val request = call.receive<SetTypeRequest>()
                    vedleggService.setDocumentCategory(uploadId, request.kategori)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }

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
