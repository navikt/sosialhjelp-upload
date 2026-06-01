package no.nav.sosialhjelp.upload.vedlegg

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import no.nav.sosialhjelp.upload.VerifiedVedleggUploadId
import no.nav.sosialhjelp.upload.verifyNavEksternRefIdExists
import no.nav.sosialhjelp.upload.verifyVedleggUploadExists

fun Route.configureVedleggRoutes() {
    val vedleggService: VedleggService by application.dependencies

    route("/vedlegg") {
        // POST /vedlegg/{uploadId}/type — set document type on a completed upload.
        // Called machine-to-machine by sosialhjelp-soknad-api; uses TokenX auth.
        route("/{uploadId}/type") {
            verifyVedleggUploadExists()

            post {
                val uploadId = call.attributes[VerifiedVedleggUploadId]
                val request = call.receive<SetTypeRequest>()
                vedleggService.setDocumentType(uploadId, request.type, request.tilleggsinfo)
                call.respond(HttpStatusCode.NoContent)
            }
        }

        // GET /vedlegg/{navEksternRefId} — return JsonVedleggSpesifikasjon for the given soknadId.
        // Called machine-to-machine by sosialhjelp-soknad-api; uses TokenX auth.
        route("/{navEksternRefId}") {
            verifyNavEksternRefIdExists()

            get {
                val navEksternRefId = call.parameters["navEksternRefId"]!!
                val vedlegg = vedleggService.getVedleggByNavEksternRefId(navEksternRefId)
                call.respond(vedlegg)
            }
        }
    }
}
