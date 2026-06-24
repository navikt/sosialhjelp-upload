package no.nav.sosialhjelp.upload.testutils

import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
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

    /** Shared notification service backed by a single LISTEN connection. */
    val notificationService: SubmissionNotificationService by lazy {
        SubmissionNotificationService(dataSource)
    }

    val jdbcUrl: String get() = instance.jdbcUrl
    val username: String get() = instance.username
    val password: String get() = instance.password

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
