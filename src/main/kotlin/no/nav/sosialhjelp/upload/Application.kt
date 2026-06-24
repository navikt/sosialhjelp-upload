@file:Suppress("LongMethod")

package no.nav.sosialhjelp.upload

import com.google.cloud.storage.StorageOptions
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.property
import io.ktor.server.plugins.di.*
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.ks.kryptering.CMSKrypteringImpl
import no.nav.sosialhjelp.upload.action.EttersendelseService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionServiceImpl
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionServiceMock
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.status.SubmissionService
import no.nav.sosialhjelp.upload.texas.TexasClient
import no.nav.sosialhjelp.upload.tus.TusUploadService
import no.nav.sosialhjelp.upload.tus.storage.ChunkStorage
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.tus.storage.GcsBucketStorage
import no.nav.sosialhjelp.upload.upload.ChunkAssemblyService
import no.nav.sosialhjelp.upload.upload.FileConversionService
import no.nav.sosialhjelp.upload.upload.MellomlagringStorageService
import no.nav.sosialhjelp.upload.upload.StaleSubmissionCleanupService
import no.nav.sosialhjelp.upload.upload.UploadProcessingService
import no.nav.sosialhjelp.upload.upload.UploadRecoveryService
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import no.nav.sosialhjelp.upload.vedlegg.VedleggService
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

private fun getDataSource(config: ApplicationConfig): DataSource {
    val user = config.property("database.user").getString()
    val password = config.property("database.password").getString()
    val jdbcUrl = config.property("database.jdbcUrl").getString()
    return HikariDataSource(
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = user
            this.password = password
            this.maximumPoolSize = 10
            this.minimumIdle = 2
            this.connectionTimeout = 30_000
            this.idleTimeout = 600_000
            this.maxLifetime = 1_800_000
        },
    )
}

private fun migrateDatabase(
    dataSource: DataSource,
    clean: Boolean,
) {
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
    val isTest = runtimeEnv == "test"
    val isLocalOrTest = runtimeEnv == "local" || isTest
    val isMock = runtimeEnv == "mock" || isLocalOrTest
    val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    val chunkStorage: ChunkStorage =
        if (isLocalOrTest) {
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
        provide<CoroutineDispatcher> { Dispatchers.IO.limitedParallelism(Int.MAX_VALUE) }
        provide<CoroutineScope> { processingScope }
        provide<SubmissionNotificationService> { SubmissionNotificationService(dataSource, notificationScope) }
        provide(TexasClient::class)
        provide(CMSKrypteringImpl::class)
        provide(VirusScanner::class)
        provide(UploadValidator::class)
        provide(FiksClient::class)
        provide(MellomlagringClient::class)
        provide(UploadRepository::class)
        provide(SubmissionQueries::class)
        provide(no.nav.sosialhjelp.upload.tus.TusSubmissionQueries::class)
        provide(no.nav.sosialhjelp.upload.action.EttersendelseSubmissionQueries::class)
        provide(no.nav.sosialhjelp.upload.upload.StaleSubmissionQueries::class)
        provide(no.nav.sosialhjelp.upload.tus.TusUploadQueries::class)
        provide(no.nav.sosialhjelp.upload.upload.UploadProcessingQueries::class)
        provide(no.nav.sosialhjelp.upload.upload.UploadRecoveryQueries::class)
        provide(ChunkAssemblyService::class)
        provide(FileConversionService::class)
        provide(MellomlagringStorageService::class)
        provide(UploadProcessingService::class)
        provide(TusUploadService::class)
        provide(SubmissionService::class)
        provide(GotenbergService::class)
        provide(EttersendelseService::class)
        provide(UploadRecoveryService::class)
        provide(StaleSubmissionCleanupService::class)
        provide(VedleggService::class)
    }
    configureSecurity()
    configureHTTP()
    configureMonitoring(appMicrometerRegistry)
    configureStatusPages()
    configureRouting()

    val scopes =
        if (!isTest) {
            val recoveryService: UploadRecoveryService by dependencies
            val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            recoveryScope.launch {
                while (true) {
                    delay(1.minutes)
                    runCatching { recoveryService.recoverAll() }
                        .onFailure { log.warn("Upload recovery sweep failed", it) }
                }
            }

            val cleanupService: StaleSubmissionCleanupService by dependencies
            val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            cleanupScope.launch {
                while (true) {
                    delay(1.minutes)
                    runCatching { cleanupService.runCleanup() }
                        .onFailure { log.warn("Retention sweep failed", it) }
                }
            }
            listOf(cleanupScope, recoveryScope)
        } else {
            emptyList()
        }

    monitor.subscribe(ApplicationStopped) {
        processingScope.cancel()
        notificationScope.cancel()
        scopes.forEach {
            it.cancel()
        }
    }
}
