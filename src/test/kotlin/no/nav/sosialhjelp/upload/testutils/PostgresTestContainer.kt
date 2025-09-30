package no.nav.sosialhjelp.upload.testutils

import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

object PostgresTestContainer {
    private val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
            start()
        }
    }

    val dataSource: DataSource by lazy {
        PGSimpleDataSource().apply {
            setUrl(instance.jdbcUrl)
            user = instance.username
            password = instance.password
        }
    }

    val dsl: DSLContext by lazy {
        DSL.using(dataSource, SQLDialect.POSTGRES)
    }

    fun migrate() {
        val load =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
        load.clean()
        load.migrate()
    }
}
