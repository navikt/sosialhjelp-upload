
package no.nav.sosialhjelp

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.requestvalidation.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.r2dbc.postgresql.api.Notification
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions.*
import kotlinx.coroutines.reactive.*
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.tusd.configureTusRoutes
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

fun Application.configureRouting() {
    val factory =
        ConnectionFactories.get(
            builder()
                .option(DATABASE, environment.config.property("database.name").getString())
                .option(USER, environment.config.property("database.user").getString())
                .option(PASSWORD, environment.config.property("database.password").getString())
                .option(HOST, environment.config.property("database.host").getString())
                .option(DRIVER, "postgresql")
                .option(
                    PORT,
                    environment.config
                        .property("database.port")
                        .getString()
                        .toInt(),
                ).build(),
        )

    install(Resources)
    install(RequestValidation) {
        validate<String> { bodyText ->
            if (!bodyText.startsWith("Hello")) {
                ValidationResult.Invalid("Body text should start with 'Hello'")
            } else {
                ValidationResult.Valid
            }
        }
    }
    install(SSE)
    routing {
        route("/sosialhjelp/upload") {
            authenticate {
                get {
                    call.respond(Foo("Hello World!"))
                }
            }
            sse("/hello") {
                send(ServerSentEvent("hello"))

                val connection =
                    Mono
                        .from(factory.create())
                        .cast(PostgresqlConnection::class.java)
                        .block() ?: error("foo")

                val listen: Flux<Notification> =
                    connection
                        .createStatement("LISTEN foo")
                        .execute()
                        .flatMap(PostgresqlResult::getRowsUpdated)
                        .thenMany(connection.notifications)

                listen
                    .asFlow()
                    .collect { notification ->
                        send(ServerSentEvent("update")) // Send notification as SSE
                    }
            }
        }

        route("/tus-hooks") { configureTusRoutes() }
    }
}

@Serializable
data class Foo(
    val message: String,
)
