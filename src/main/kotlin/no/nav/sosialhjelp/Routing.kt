package no.nav.sosialhjelp

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.tusd.configureTusRoutes

fun Application.configureRouting() {
    install(Resources)
    install(RequestValidation) {
        validate<String> { bodyText ->
            if (!bodyText.startsWith("Hello")) {
                ValidationResult.Invalid("Body text should start with 'Hello'")
            } else {
                ValidationResult.Valid
            }
        }
    }
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            authenticate {
                get {
                    call.respond(Foo("Hello World!"))
                }
            }
            sse("/hello") {
                send(ServerSentEvent("world"))
            }
        }

        route("/tus-hooks") { configureTusRoutes() }
    }
}

@Serializable
data class Foo(
    val message: String,
)
