package tusd

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import no.nav.sosialhjelp.tusd.dto.HookType
import java.net.URI
import java.util.concurrent.TimeUnit

fun Route.configureTusRoutes() {
    val jwtIssuer = environment.config.property("jwt.issuer").getString()
    val jwtAudience = environment.config.property("jwt.audience").getString()
    val jwkProvider =
        JwkProviderBuilder(URI("$jwtIssuer/jwks").toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    post("/pre-create") {
        val request = call.receive<HookRequest>()

        assert(request.type == HookType.HOOK_PRE_CREATE)
        call.respond(HookResponse())
    }
    post("/post-create") {
        call.respondText("Hello World!")
    }
}
