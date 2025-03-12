package no.nav.sosialhjelp.progress

import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.api.PostgresqlResult
import kotlinx.coroutines.reactive.*
import reactor.core.publisher.Mono

fun Route.configureProgressRoutes() {
    val reactiveConnectionFactory = ReactiveConnectionFactory(environment)

    sse("/hello") {
        send(ServerSentEvent("hello"))

        val connection =
            Mono
                .from(reactiveConnectionFactory.createConnection())
                .cast(PostgresqlConnection::class.java)
                .block() ?: error("could not connect to database")

        connection
            .createStatement("LISTEN foo")
            .execute()
            .flatMap(PostgresqlResult::getRowsUpdated)
            .thenMany(connection.notifications)
            .asFlow()
            .collect { send(ServerSentEvent("update")) }
    }
}
