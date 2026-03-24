package no.nav.sosialhjelp.upload

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import no.nav.sosialhjelp.upload.action.configureActionRoutes
import no.nav.sosialhjelp.upload.documents.configureDocumentRoutes
import no.nav.sosialhjelp.upload.status.configureStatusRoutes
import no.nav.sosialhjelp.upload.tus.configureTusRoutes

private const val TUS_BASE_PATH = "/tus/files"

fun Application.configureRouting() {
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
                configureDocumentRoutes()
                configureActionRoutes()
                route(TUS_BASE_PATH) {
                    configureTusRoutes(TUS_BASE_PATH)
                }
            }
        }
    }
}
