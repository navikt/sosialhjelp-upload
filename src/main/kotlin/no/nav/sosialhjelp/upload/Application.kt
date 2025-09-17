package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.plugins.di.dependencies
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.SSL
import kotlinx.io.files.Path
import no.nav.sosialhjelp.upload.action.DownstreamUploadService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.tusd.TusService
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.pdf.ThumbnailService
import no.nav.sosialhjelp.upload.status.DocumentStatusService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import org.jooq.impl.DefaultConfiguration
import org.flywaydb.core.Flyway

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

object DatabaseFactory {
    lateinit var dsl: DSLContext
        private set

    fun init(environment: ApplicationEnvironment) {
        val dbname = environment.config.property("database.name").getString()
        val user = environment.config.property("database.user").getString()
        val password = environment.config.property("database.password").getString()
        val host = environment.config.property("database.host").getString()
        val port = environment.config.property("database.port").getString().toInt()

        // Run Flyway migrations using JDBC
        val jdbcUrl = "jdbc:postgresql://$host:$port/$dbname"
        val load = Flyway.configure()
            .dataSource(jdbcUrl, user, password)
            .locations("classpath:db/migration")
            // TODO: FJERN FØR PRODSETTING
            .cleanDisabled(false)
            .load()
        load.clean()
        load.migrate()


        val options = ConnectionFactoryOptions.builder()
            .option(DRIVER, "postgresql")
            .option(HOST, host)
            .option(PORT, port)
            .option(USER, user)
            .option(PASSWORD, password)
            .option(DATABASE, dbname)
            .option(SSL, true)
            .build()
        val connectionFactory = ConnectionFactories.get(options)
        val config = DefaultConfiguration().derive(connectionFactory).derive(SQLDialect.POSTGRES)
        dsl = DSL.using(config)
        DocumentChangeNotifier.dsl = dsl
    }
}

fun Application.module() {
    DatabaseFactory.init(environment)
    dependencies {
        provide { DatabaseFactory.dsl }
        provide { this@module.environment.log }
        provide(VirusScanner::class)
        provide(UploadValidator::class)
        provide(FiksClient::class)
        provide(TusService::class)
        provide(UploadRepository::class)
        provide(PageRepository::class)
        provide(DocumentRepository::class)
        provide(ThumbnailService::class)
        provide(DocumentStatusService::class)
        provide(GotenbergService::class)
        provide(FilePathFactory::class)
        provide(DownstreamUploadService::class)
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}
