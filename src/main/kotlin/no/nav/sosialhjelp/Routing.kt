
package no.nav.sosialhjelp

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.progress.configureProgressRoutes
import no.nav.sosialhjelp.tusd.configureTusRoutes
import java.io.File

fun Application.configureRouting() {
    install(Resources)
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            authenticate {
            }
            configureProgressRoutes()
            // todo: user permission check for files
            staticFiles("/thumbnails", File("./tusd-data"))
        }

        route("/tus-hooks") { configureTusRoutes() }
    }
}
