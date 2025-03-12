
package no.nav.sosialhjelp

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.progress.configureProgressRoutes
import no.nav.sosialhjelp.tusd.configureTusRoutes

fun Application.configureRouting() {
    install(Resources)
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            authenticate {
            }
            configureProgressRoutes()
        }

        route("/tus-hooks") { configureTusRoutes() }
    }
}
