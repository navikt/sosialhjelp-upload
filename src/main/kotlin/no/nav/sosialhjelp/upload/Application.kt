package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.plugins.di.*
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.DownstreamUploadService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.fs.FileSystemStorage
import no.nav.sosialhjelp.upload.fs.GcpBucketStorage
import no.nav.sosialhjelp.upload.fs.Storage
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.status.DocumentStatusService
import no.nav.sosialhjelp.upload.texas.TexasClient
import no.nav.sosialhjelp.upload.tusd.TusService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

private fun getDataSource(config: ApplicationConfig): DataSource {
    val user = config.property("database.user").getString()
    val password = config.property("database.password").getString()
    val jdbcUrl = config.property("database.jdbcUrl").getString()
    return PGSimpleDataSource().apply {
        setUrl(jdbcUrl)
        setUser(user)
        setPassword(password)
    }
}

private fun migrateDatabase(dataSource: DataSource) {
    val load =
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("db/migration")
            .load()
    load.migrate()
}

fun Application.module() {
    val dataSource = getDataSource(environment.config)
    migrateDatabase(dataSource)
    dependencies {
        provide<DataSource> {
            dataSource
        }
        provide<DSLContext> {
            DSL.using(dataSource, SQLDialect.POSTGRES)
        }
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
                    this@module
                        .environment.config
                        .property("storage.gcsCredentials")
                        .getString(),
                )
            }
        }
        provide(TexasClient::class)
        provide(CMSKrypteringImpl::class)
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
