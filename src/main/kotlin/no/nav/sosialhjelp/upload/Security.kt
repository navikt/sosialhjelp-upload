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
            verifier(jwkProvider, jwtIssuer)
            validate { credential ->
                if (credential.payload.getClaim("client_id") == null) {
                    this@configureSecurity.log.warn("Missing client_id in JWT")
                    return@validate null
                }
                if (credential.payload.getClaim("client_id").asString() != jwtAudience) {
                    this@configureSecurity.log.warn("Wrong client_id in JWT")
                    return@validate null
                }
                if ("idporten-loa-high" !=
                    credential.payload
                        .getClaim("acr")
                        .asString()
                        .lowercase()
                ) {
                    this@configureSecurity.log.warn("Wrong acr in JWT. Was ${credential.payload.getClaim("acr").asString()}")
                    return@validate null
                }
                JWTPrincipal(credential.payload)
            }
        }
    }
}
