package no.nav.sosialhjelp.upload

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.util.cio.ChannelWriteException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<ChannelWriteException> { call, _ ->
            // Client disconnected (tab closed, navigated away, network drop) — normal for SSE
            this@configureStatusPages.environment.log.debug("Client disconnected from ${call.request.uri}")
        }
        exception<Throwable> { call, cause ->
            this@configureStatusPages.environment.log.error("Got error on call to ${call.request.uri}", cause)
            call.respondText(text = "500: $cause", status = HttpStatusCode.InternalServerError)
        }
    }
}
