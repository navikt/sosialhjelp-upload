package no.nav.sosialhjelp.tusd

import HookType
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
        call.application.environment.log
            .info("Received hook request type ${request.Type}")
        call.respond(
            when (request.Type) {
                HookType.PreCreate -> tusService.preCreate(request)
                HookType.PostCreate -> tusService.postCreate(request)
                HookType.PostFinish -> tusService.postFinish(request)
                else -> HookResponse()
            },
        )
    }
}
