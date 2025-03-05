package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import org.koin.ktor.ext.inject

fun Route.configureTusRoutes() {
    val whatever by inject<TusService>()

    post {
        val request = call.receive<HookRequest>()

        when (request.Type) {
            HookType.PRE_CREATE -> HookResponse()
            else -> HookResponse()
        }

        call.respond(HookResponse())
    }
}
