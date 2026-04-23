package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.plugins.di.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.EttersendelseService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionServiceImpl
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionServiceMock
import no.nav.sosialhjelp.upload.database.RetentionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRecoveryService
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.status.SubmissionService
import no.nav.sosialhjelp.upload.storage.ChunkStorage
import no.nav.sosialhjelp.upload.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.storage.GcsBucketStorage
import com.google.cloud.storage.StorageOptions
import no.nav.sosialhjelp.upload.texas.TexasClient
import no.nav.sosialhjelp.upload.tus.TusUploadService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
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

private fun migrateDatabase(dataSource: DataSource, clean: Boolean) {
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("db/migration")
        .validateMigrationNaming(true)
        .also {
            if (clean) {
                it.cleanDisabled(false)
            }
        }
        .load()
        .also {
            if (clean) {
                it.clean()
            }
        }
        .migrate()
}

fun Application.module() {
    val dataSource = getDataSource(environment.config)
    migrateDatabase(dataSource, clean = environment.config.property("database.cleanOnStart").getString() == "true")
    val runtimeEnv = this@module.property<String>("runtimeEnv")
    val isLocal = runtimeEnv == "local"
    val isMock = runtimeEnv == "mock" || isLocal
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val chunkStorage: ChunkStorage = if (isLocal) {
        FileSystemStorage()
    } else {
        val bucketName = environment.config.property("gcs.bucketName").getString()
        GcsBucketStorage(StorageOptions.getDefaultInstance().service, bucketName)
    }
    dependencies {
        provide<PrometheusMeterRegistry> { appMicrometerRegistry }
        provide<MeterRegistry> { appMicrometerRegistry }
        provide<DataSource> { dataSource }
        provide<DSLContext> { DSL.using(dataSource, SQLDialect.POSTGRES) }
        provide<EncryptionService> {
            if (isMock) {
                create(EncryptionServiceMock::class)
            } else {
                create(EncryptionServiceImpl::class)
            }
        }
        provide<ChunkStorage> { chunkStorage }
        provide<CoroutineDispatcher> { Dispatchers.IO }
        provide<CoroutineScope> { processingScope }
        provide<SubmissionNotificationService> { SubmissionNotificationService(dataSource, notificationScope) }
        provide(TexasClient::class)
        provide(CMSKrypteringImpl::class)
        provide(VirusScanner::class)
        provide(UploadValidator::class)
        provide(FiksClient::class)
        provide(MellomlagringClient::class)
        provide(TusUploadService::class)
        provide(UploadRepository::class)
        provide(SubmissionRepository::class)
        provide(SubmissionService::class)
        provide(GotenbergService::class)
        provide(EttersendelseService::class)
        provide(UploadRecoveryService::class)
        provide(RetentionService::class)
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring(appMicrometerRegistry)
    configureStatusPages()
    configureRouting()

    val recoveryService: UploadRecoveryService by dependencies
    val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    recoveryScope.launch {
        while (true) {
            delay(1.minutes)
            runCatching { recoveryService.recoverAll() }
                .onFailure { log.warn("Upload recovery sweep failed", it) }
        }
    }

    val retentionService: RetentionService by dependencies
    val retentionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    retentionScope.launch {
        while (true) {
            delay(1.minutes)
            runCatching { retentionService.runRetention() }
                .onFailure { log.warn("Retention sweep failed", it) }
        }
    }
}
