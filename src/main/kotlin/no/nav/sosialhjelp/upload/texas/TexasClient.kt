package no.nav.sosialhjelp.upload.texas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.di.annotations.Property
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Instant

class TexasClient(
    @Property("texas.url") val texasUrl: String,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    val client by lazy {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
        }
    }

    @Volatile private var cachedToken: String? = null

    @Volatile private var tokenExpiresAt: Instant = Instant.MIN

    suspend fun getMaskinportenToken(): String {
        val now = Instant.now()
        cachedToken?.let { token ->
            if (now.isBefore(tokenExpiresAt)) return token
        }
        return fetchAndCacheToken()
    }

    private suspend fun fetchAndCacheToken(): String {
        val response =
            client
                .post(texasUrl) {
                    accept(ContentType.Application.Json)
                    contentType(ContentType.Application.Json)
                    setBody(maskinportenParams)
        }

        if (response.status.isSuccess()) {
            val body = response.body<TokenSuccessResponse>()
            cachedToken = body.accessToken
            // Subtract 30s buffer to avoid using a token that expires in transit
            tokenExpiresAt = Instant.now().plusSeconds(body.expiresInSeconds.toLong() - 30)
            return body.accessToken
        }

        val body = response.body<TokenErrorResponse>()
        logger.error("Failed to get token from Texas: $body, status: ${response.status}")
        throw TexasTokenException("Failed to get token from Texas")
    }
}

class TexasTokenException(
    message: String,
) : RuntimeException(message)

private val maskinportenParams: Map<String, String> =
    mapOf("identity_provider" to "maskinporten", "target" to "ks:fiks")

@Serializable
data class TokenSuccessResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresInSeconds: Int,
    @SerialName("token_type")
    val tokenType: String,
)

@Serializable
data class TokenErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String,
)
