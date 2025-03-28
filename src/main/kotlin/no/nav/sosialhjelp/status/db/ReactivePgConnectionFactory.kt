package no.nav.sosialhjelp.status.db

import io.ktor.server.application.*
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import reactor.core.publisher.Mono

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
