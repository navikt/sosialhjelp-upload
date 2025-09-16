package no.nav.sosialhjelp.upload

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URI
import java.util.concurrent.TimeUnit

fun Application.configureSecurity() {
    val jwtIssuer = environment.config.property("jwt.issuer").getString()

    val jwtAudience = environment.config.property("jwt.audience").getString()

    val jwkProvider =
        JwkProviderBuilder(URI("$jwtIssuer/jwks").toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    authentication {
        jwt {
            verifier(jwkProvider, jwtIssuer)
            validate { credential -> if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null }
        }
    }
}
