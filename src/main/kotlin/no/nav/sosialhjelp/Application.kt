package no.nav.sosialhjelp

import io.ktor.server.application.*
import no.nav.sosialhjelp.lol.MainPdfTable
import no.nav.sosialhjelp.lol.ThumbnailTable
import no.nav.sosialhjelp.lol.UploadTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

object DatabaseFactory {
    fun init() {
        Database.connect(
            "jdbc:h2:file:./testdb;DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.create(UploadTable)
            SchemaUtils.create(ThumbnailTable)
            SchemaUtils.create(MainPdfTable)
        }
    }
}

fun Application.module() {
    DatabaseFactory.init()
    configureSecurity()
    configureHTTP()
    configureMonitoring()
//    configureDatabases()
    configureFrameworks()
    configureSerialization()
    configureStatusPages()
//    configureAdministration()
    configureRouting()
}
