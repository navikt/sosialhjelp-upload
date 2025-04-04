package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import org.koin.ktor.ext.inject

fun Route.configureTusRoutes() {
    val tusService by inject<TusService>()

    post {
        val request = call.receive<HookRequest>()
        val personIdent = call.principal<JWTPrincipal>()?.subject

        if (personIdent == null) {
            call.respond(HttpStatusCode.Unauthorized, "missing or invalid token")
            return@post
        }

        environment.log.info("got hook request of type ${request.type}")

        call.respond(
            when (request.type) {
                HookType.PreCreate -> tusService.preCreate(request, personIdent)
                HookType.PostFinish -> tusService.postFinish(request)
                else -> {
                    environment.log.warn("got unhandled hook request of type ${request.type}")
                    HookResponse()
                }
            },
        )
    }
}
