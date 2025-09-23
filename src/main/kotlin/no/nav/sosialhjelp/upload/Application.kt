package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import no.nav.sosialhjelp.upload.action.DownstreamUploadService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.fs.FileSystemStorage
import no.nav.sosialhjelp.upload.fs.GcpBucketStorage
import no.nav.sosialhjelp.upload.fs.Storage
import no.nav.sosialhjelp.upload.pdf.GotenbergService
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
        provide<Storage> {
            val isLocal =
                this@module
                    .environment.config
                    .property("runtimeEnv")
                    .getString() == "local"
            if (isLocal) {
                FileSystemStorage(
                    this@module
                        .environment.config
                        .property("storage.basePath")
                        .getString(),
                )
            } else {
                GcpBucketStorage(
                    this@module
                        .environment.config
                        .property("storage.bucketName")
                        .getString(),
                )
            }
        }
        provide(VirusScanner::class)
        provide(UploadValidator::class)
        provide(FiksClient::class)
        provide(TusService::class)
        provide(UploadRepository::class)
        provide(DocumentRepository::class)
        provide(DocumentStatusService::class)
        provide(GotenbergService::class)
        provide(DownstreamUploadService::class)
        provide(DocumentNotificationService::class)
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring()
    configureStatusPages()
    configureRouting()
}
