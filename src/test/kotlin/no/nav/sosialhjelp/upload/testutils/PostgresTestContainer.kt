package no.nav.sosialhjelp.upload.testutils

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.testcontainers.containers.PostgreSQLContainer

object PostgresTestContainer {
    private val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
            start()
        }
    }

    fun connectAndStart(): DSLContext {
        val jdbcUrl = instance.jdbcUrl
        val load =
            Flyway
                .configure()
                .dataSource(jdbcUrl, instance.username, instance.password)
                .locations("classpath:db/migration")
                // TODO: FJERN FØR PRODSETTING
                .cleanDisabled(false)
                .load()
        load.clean()
        load.migrate()
        val dsl = DSL.using(jdbcUrl, instance.username, instance.password)
        DocumentChangeNotifier.dsl = dsl

        return dsl
    }
}
