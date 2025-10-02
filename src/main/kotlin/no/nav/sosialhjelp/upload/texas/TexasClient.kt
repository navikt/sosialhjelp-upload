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

    suspend fun getMaskinportenToken(): String {
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
                response.body<TokenErrorResponse>()
            }
        if (body is TokenResponse.Success) {
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
