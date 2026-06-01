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

    val tokenxJwksUri = environment.config.property("tokenx.jwksUri").getString()
    val tokenxIssuer = environment.config.property("tokenx.issuer").getString()
    val tokenxClientId = environment.config.property("tokenx.clientId").getString()

    val tokenxJwkProvider =
        JwkProviderBuilder(URI(tokenxJwksUri).toURL())
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
                    this@configureSecurity.log.warn("Wrong acr level in JWT")
                    return@validate null
                }
                val subject = credential.payload.subject
                if (subject == null || !subject.matches(Regex("\\d{11}"))) {
                    this@configureSecurity.log.warn("JWT subject is missing or not a valid 11-digit personnummer")
                    return@validate null
                }
                JWTPrincipal(credential.payload)
            }
        }

        // TokenX provider for machine-to-machine calls from sosialhjelp-soknad-api.
        // Nais injects TOKEN_X_CLIENT_ID as the expected audience (e.g. "prod-gcp:teamdigisos:sosialhjelp-upload").
        jwt("tokenx") {
            verifier(tokenxJwkProvider, tokenxIssuer)
            validate { credential ->
                val audience = credential.payload.audience
                if (audience == null || tokenxClientId !in audience) {
                    this@configureSecurity.log.warn("TokenX JWT has wrong or missing audience")
                    return@validate null
                }
                JWTPrincipal(credential.payload)
            }
        }
    }
}
