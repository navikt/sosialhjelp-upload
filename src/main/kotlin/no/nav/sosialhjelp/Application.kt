package no.nav.sosialhjelp

import io.ktor.server.application.*
import io.ktor.server.plugins.di.dependencies
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.Dispatchers
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.database.schema.UploadTable

import no.nav.sosialhjelp.tusd.TusService
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

object DatabaseFactory {
    suspend fun init(environment: ApplicationEnvironment) {
        val dbname = environment.config.property("database.name").getString()
        val user = environment.config.property("database.user").getString()
        val password = environment.config.property("database.password").getString()
        val host = environment.config.property("database.host").getString()
        val port =
            environment.config
                .property("database.port")
                .getString()
                .toInt()

        val dbUrl = "r2dbc:postgresql://$host:$port/$dbname"
        R2dbcDatabase.connect(
            dbUrl,
            databaseConfig = {
                useNestedTransactions = true
                defaultMaxAttempts = 1
                connectionFactoryOptions {
                    option(ConnectionFactoryOptions.USER, user)
                    option(ConnectionFactoryOptions.PASSWORD, password)
                }
            },
        )
        suspendTransaction(Dispatchers.IO) {
            SchemaUtils.create(DocumentTable)
            SchemaUtils.create(PageTable)
            SchemaUtils.create(UploadTable)
        }
    }
}

suspend fun Application.module() {
    DatabaseFactory.init(environment)
    dependencies {
        provide { TusService(this@module.environment) }
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring()
//    configureDatabases()
    configureStatusPages()
//    configureAdministration()
    configureRouting()
}
