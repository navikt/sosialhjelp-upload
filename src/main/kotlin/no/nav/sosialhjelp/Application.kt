package no.nav.sosialhjelp

import io.ktor.server.application.*
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.database.schema.UploadTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

object DatabaseFactory {
    fun init(environment: ApplicationEnvironment) {
        val dbname = environment.config.property("database.name").getString()
        val user = environment.config.property("database.user").getString()
        val password = environment.config.property("database.password").getString()
        val host = environment.config.property("database.host").getString()
        val port =
            environment.config
                .property("database.port")
                .getString()
                .toInt()

        val dbUrl = "jdbc:postgresql://$host:$port/$dbname"
        Database.connect(
            dbUrl,
            driver = "org.postgresql.Driver",
            user = user,
            password = password,
        )
        transaction {
            SchemaUtils.create(DocumentTable)
            SchemaUtils.create(PageTable)
            SchemaUtils.create(UploadTable)
        }
    }
}

fun Application.module() {
    DatabaseFactory.init(environment)
    configureSecurity()
    configureHTTP()
    configureMonitoring()
//    configureDatabases()
    configureFrameworks()
    configureStatusPages()
//    configureAdministration()
    configureRouting()
}
