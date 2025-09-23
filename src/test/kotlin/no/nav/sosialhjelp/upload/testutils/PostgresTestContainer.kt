package no.nav.sosialhjelp.upload.testutils

import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.impl.DSL
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
                .cleanDisabled(false)
                .load()
        load.clean()
        load.migrate()
        val dsl = DSL.using(jdbcUrl, instance.username, instance.password)
        DocumentChangeNotifier.dsl = dsl

        return dsl
    }
}
