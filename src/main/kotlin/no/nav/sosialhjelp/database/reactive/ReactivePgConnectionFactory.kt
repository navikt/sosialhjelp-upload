package no.nav.sosialhjelp.database.reactive

import io.ktor.server.application.*
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import reactor.core.publisher.Mono

/**
 * Factory for creating a reactive PostgreSQL connection.
 * It might seem a little odd to have two different database libraries in the same project.
 * The reason for this is that Exposed is a blocking library, and we want to use a reactive
 * library for listening to database events for the event return channel.
 *
 * Minste motstands vei ¯\_(ツ)_/¯
 */
class ReactivePgConnectionFactory(
    environment: ApplicationEnvironment,
) {
    private val connectionFactory: ConnectionFactory =
        ConnectionFactories.get(
            ConnectionFactoryOptions
                .builder()
                .option(ConnectionFactoryOptions.DATABASE, environment.config.property("database.name").getString())
                .option(ConnectionFactoryOptions.USER, environment.config.property("database.user").getString())
                .option(ConnectionFactoryOptions.PASSWORD, environment.config.property("database.password").getString())
                .option(ConnectionFactoryOptions.HOST, environment.config.property("database.host").getString())
                .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                .option(
                    ConnectionFactoryOptions.PORT,
                    environment.config
                        .property("database.port")
                        .getString()
                        .toInt(),
                ).build(),
        )

    fun createConnection() =
        Mono
            .from(connectionFactory.create())
            .cast(PostgresqlConnection::class.java)
            .block() ?: error("could not connect to database")
}
