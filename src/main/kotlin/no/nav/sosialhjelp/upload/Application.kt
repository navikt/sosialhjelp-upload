package no.nav.sosialhjelp.upload

import ch.qos.logback.core.net.ssl.SSL
import io.ktor.server.application.*
import io.ktor.server.plugins.di.dependencies
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.SSL
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import io.r2dbc.spi.Option
import kotlinx.io.files.Path
import no.nav.sosialhjelp.upload.action.DownstreamUploadService
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.pdf.ThumbnailService
import no.nav.sosialhjelp.upload.status.DocumentStatusService
import no.nav.sosialhjelp.upload.tusd.TusService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.VirusScanner
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import org.postgresql.ssl.PGjdbcHostnameVerifier
import kotlin.math.log

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain
        .main(args)
}

object DatabaseFactory {
    lateinit var dsl: DSLContext
        private set

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
        val sslCert = environment.config.property("database.sslCert").getString()
        val sslKey = environment.config.property("database.sslKey").getString()
        val sslKeyPK8 = environment.config.property("database.sslKeyPK8").getString()
        val sslMode = environment.config.property("database.sslMode").getString()
        val sslRootCert = environment.config.property("database.sslRootCert").getString()
        val jdbcUrl = environment.config.property("database.jdbcUrl").getString()
        val r2dbcUrl = jdbcUrl.replace("jdbc", "r2dbc")
        val url2 =
            "r2dbcs:postgresql://$user:$password@$host:$port/sosialhjelp-upload?sslcert=$sslCert&sslkey=$sslKey&sslmode=$sslMode&sslrootcert=$sslRootCert"

        // Run Flyway migrations using JDBC
        val load =
            Flyway
                .configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration")
                // TODO: FJERN FØR PRODSETTING
                .cleanDisabled(false)
                .load()
        load.clean()
        load.migrate()

        val options =
            ConnectionFactoryOptions
                .builder()
                .option(DRIVER, "postgresql")
                .option(HOST, host)
                .option(PORT, port)
                .option(USER, user)
                .option(PASSWORD, password)
                .option(DATABASE, dbname)
                .apply {
                    if (sslMode == "none") {
                        return@apply
                    }
                    option(SSL, true)
                    option(Option.valueOf("sslMode"), sslMode)
                    option(Option.valueOf("sslCert"), sslCert)
                    option(Option.valueOf("sslKey"), sslKey)
                    option(Option.valueOf("sslRootCert"), sslRootCert)
                    option(Option.valueOf("sslSni"), false)
                }.build()
                .also { environment.log.info(it.toString()) }
        val connectionConfig =
            PostgresqlConnectionConfiguration
                .builder()
                .host(
                    host,
                ).port(port)
                .username(user)
                .password(password)
                .sslRootCert(sslRootCert)
                .sslCert(sslCert)
                .sslMode(SSLMode.valueOf(sslMode.uppercase().replace("-", "_")))
                .database(dbname)
                .build()
        val connectionFactory = PostgresqlConnectionFactory(connectionConfig)
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
