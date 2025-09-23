package no.nav.sosialhjelp.upload.tusd

import HookType
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import no.nav.sosialhjelp.upload.tusd.dto.HookResponse

fun Route.configureTusRoutes() {
    val tusService: TusService by application.dependencies

    post {
        val request = call.receive<HookRequest>()
        val personIdent = call.principal<JWTPrincipal>()?.subject

        if (personIdent == null) {
            environment.log.info("Mangler personident")
            call.respond(HttpStatusCode.Unauthorized, "missing or invalid token")
            return@post
        }

        environment.log.info("got hook request of type ${request.type}")

        runCatching {
            call.respond(
                when (request.type) {
                    HookType.PreCreate -> tusService.preCreate(request, personIdent)
                    HookType.PreFinish -> tusService.validateUpload(request)
                    HookType.PostFinish -> tusService.postFinish(request)

                    HookType.PreTerminate -> tusService.preTerminate(request, personIdent)
                    HookType.PostTerminate -> tusService.postTerminate(request, personIdent)
                    else -> {
                        environment.log.warn("got unhandled hook request of type ${request.type}")
                        HookResponse()
                    }
                },
            )
        }.onFailure { cause -> environment.log.error("Something happened during Tus hook", cause) }.getOrThrow()
    }
}
