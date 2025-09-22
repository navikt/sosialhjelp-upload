package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import no.nav.sosialhjelp.upload.action.DownstreamUploadService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.pdf.ThumbnailService
import no.nav.sosialhjelp.upload.status.DocumentStatusService
import no.nav.sosialhjelp.upload.tusd.TusService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.impl.DSL

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

object DatabaseFactory {
    lateinit var dsl: DSLContext
        private set

    fun init(environment: ApplicationEnvironment) {
        val user = environment.config.property("database.user").getString()
        val password = environment.config.property("database.password").getString()
        val jdbcUrl = environment.config.property("database.jdbcUrl").getString()

        val load =
            Flyway
                .configure()
                .dataSource(jdbcUrl, user, password)
                .locations("db/migration")
                .validateMigrationNaming(true)
                // TODO: FJERN FØR PRODSETTING
                .cleanDisabled(false)
                .load()
        load.clean()
        load.migrate()

        dsl = DSL.using(jdbcUrl, user, password)
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
        provide(DocumentNotificationService::class)
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}
