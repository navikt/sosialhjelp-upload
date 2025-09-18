package no.nav.sosialhjelp.upload

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import java.net.URI
import java.util.concurrent.TimeUnit

fun Application.configureSecurity() {
    val jwtIssuer = environment.config.property("jwt.issuer").getString()
    val jwksUri = environment.config.property("jwt.jwks_uri").getString()

    val jwtAudience = environment.config.property("jwt.audience").getString()

    val jwkProvider =
        JwkProviderBuilder(URI(jwksUri).toURL())
            .cached(10, 24, TimeUnit.HOURS)
            .rateLimited(10, 1, TimeUnit.MINUTES)
            .build()

    authentication {
        jwt {
            verifier(jwkProvider, jwtIssuer) {

            }
            validate { credential ->
                this@configureSecurity.log.info(credential.payload.toString())
                if (
                    credential.payload.audience.contains(jwtAudience) &&
                    "idporten_loa_high" in
                    credential.payload
                        .getClaim("acr")
                        .asString()
                        .lowercase()
                ) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
        }
    }
}
