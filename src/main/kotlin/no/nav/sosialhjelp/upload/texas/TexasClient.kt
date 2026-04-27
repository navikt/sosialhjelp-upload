package no.nav.sosialhjelp.upload.texas

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
        val body =
            try {
                response.body<TokenResponse.Success>()
            } catch (e: Exception) {
                response.body<TokenResponse.Error>()
            }
        if (body is TokenResponse.Success) {
            cachedToken = body.accessToken
            // Subtract 30s buffer to avoid using a token that expires in transit
            tokenExpiresAt = Instant.now().plusSeconds(body.expiresInSeconds.toLong() - 30)
            return body.accessToken
        } else {
            logger.error("Failed to get token from Texas: ${(body as TokenResponse.Error).error}, status: ${body.status}")
            throw RuntimeException("Failed to get token from Texas")
        }
    }
}

private val maskinportenParams: Map<String, String> = mapOf("identity_provider" to "maskinporten", "target" to "ks:fiks")

@Serializable
sealed class TokenResponse {
    @Serializable
    data class Success(
        @SerialName("access_token")
        val accessToken: String,
        @SerialName("expires_in")
        val expiresInSeconds: Int,
        @SerialName("token_type")
        val tokenType: String,
    ) : TokenResponse()

    data class Error(
        val error: TokenErrorResponse,
        val status: HttpStatusCode,
    ) : TokenResponse()
}

@Serializable
data class TokenErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String,
)
