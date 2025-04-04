package no.nav.sosialhjelp.testutils

import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.testcontainers.containers.PostgreSQLContainer

object PostgresTestContainer {
    private val instance: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("testdb")
            withUsername("test")
            withPassword("test")
            start()
        }
    }

    fun connectAndStart(): Database {
        val db =
            Database.connect(
                url = instance.jdbcUrl,
                driver = "org.postgresql.Driver",
                user = instance.username,
                password = instance.password,
            )

        transaction {
            SchemaUtils.create(DocumentTable, UploadTable)
        }

        return db
    }
}
