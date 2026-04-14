package no.nav.sosialhjelp.upload.testutils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date

object JwtTestUtils {
    const val ISSUER = "test-issuer"
    const val CLIENT_ID = "test-client-id"
    const val KID = "test-kid"

    val keyPair by lazy {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        gen.generateKeyPair()
    }

    val jwksJson: String by lazy {
        val pub = keyPair.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val n = encoder.encodeToString(pub.modulus.toByteArray())
        val e = encoder.encodeToString(pub.publicExponent.toByteArray())
        """{"keys":[{"kty":"RSA","kid":"$KID","use":"sig","alg":"RS256","n":"$n","e":"$e"}]}"""
    }

    fun issueToken(
        subject: String = "12345678910",
        acr: String = "idporten-loa-high",
    ): String {
        val algorithm = Algorithm.RSA256(null, keyPair.private as RSAPrivateKey)
        return JWT.create()
            .withIssuer(ISSUER)
            .withSubject(subject)
            .withClaim("client_id", CLIENT_ID)
            .withClaim("acr", acr)
            .withKeyId(KID)
            .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000L))
            .sign(algorithm)
    }
}
