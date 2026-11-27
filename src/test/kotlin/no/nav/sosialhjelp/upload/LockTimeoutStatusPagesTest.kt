package no.nav.sosialhjelp.upload

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLockTimeoutException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LockTimeoutStatusPagesTest {
    private fun timeout(mode: NavEksternRefIdLock.Mode) = NavEksternRefIdLockTimeoutException(mode, RuntimeException())

    @Test
    fun `shared lock timeout is 503 with Retry-After, exclusive is 409 UPLOADS_IN_PROGRESS`() =
        testApplication {
            application {
                this.install(ContentNegotiation) { json() }
                configureStatusPages()
                routing {
                    get("/shared") { throw timeout(NavEksternRefIdLock.Mode.SHARED) }
                    get("/exclusive") { throw timeout(NavEksternRefIdLock.Mode.EXCLUSIVE) }
                }
            }

            val shared = client.get("/shared")
            assertEquals(HttpStatusCode.ServiceUnavailable, shared.status)
            assertEquals("5", shared.headers[HttpHeaders.RetryAfter])

            val exclusive = client.get("/exclusive")
            assertEquals(HttpStatusCode.Conflict, exclusive.status)
            assertTrue(exclusive.bodyAsText().contains("UPLOADS_IN_PROGRESS"))
        }
}
