package no.nav.sosialhjelp.progress

import io.ktor.server.application.*
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions

class ReactiveConnectionFactory(
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

    fun createConnection() = connectionFactory.create()
}
