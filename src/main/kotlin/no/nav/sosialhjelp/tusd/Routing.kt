package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse

fun Route.configureTusRoutes() {
//    val tusService by inject<TusService>()
    val tusService = TusService(environment)
    post {
        val request = call.receive<HookRequest>()
        val personIdent = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")

        call.application.environment.log
            .info("Received hook request type ${request.Type}")
        call.respond(
            when (request.Type) {
                HookType.PreCreate -> tusService.preCreate(request, personIdent)
                HookType.PostCreate -> tusService.postCreate(request)
                HookType.PostFinish -> tusService.postFinish(request)
                else -> HookResponse()
            },
        )
    }
}
