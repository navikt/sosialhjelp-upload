package no.nav.sosialhjelp.upload

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.upload.action.configureActionRoutes
import no.nav.sosialhjelp.upload.status.configureStatusRoutes
import no.nav.sosialhjelp.upload.tusd.configureTusRoutes
import java.io.File

fun Application.configureRouting() {
    val storageBasePath = environment.config.propertyOrNull("storage.basePath")?.getString()
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            route("/internal/isAlive") {
                get {
                    call.respondText("I'm alive!", ContentType.Text.Plain)
                }
            }
            authenticate {
                configureStatusRoutes()
                configureActionRoutes()
            }
            if (environment.config
                    .propertyOrNull("runtimeEnv")
                    ?.getString() == "local"
            ) {
                if (storageBasePath != null) {
                    staticFiles("/files", File(storageBasePath))
                } else {
                    environment.log.warn("runtimeEnv is local, but storage.basePath is not set. Files will not be served.")
                }
            }
        }

        route("/tus-hooks") {
            authenticate {
                configureTusRoutes()
            }
        }
    }
}
