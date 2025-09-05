package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.upload.action.configureActionRoutes
import no.nav.sosialhjelp.upload.status.configureStatusRoutes
import no.nav.sosialhjelp.upload.tusd.configureTusRoutes
import java.io.File

fun Application.configureRouting() {
    install(Resources)
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            authenticate {
                configureStatusRoutes()
                configureActionRoutes()
                // todo: user permission check for files
            }
            staticFiles("/thumbnails", File("./tusd-data"))
        }

        route("/tus-hooks") { authenticate { configureTusRoutes() } }
    }
}
