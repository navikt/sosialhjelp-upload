package no.nav.sosialhjelp.upload

import io.ktor.server.application.*
import io.ktor.server.plugins.di.*
import io.r2dbc.postgresql.PostgresqlConnectionConfiguration
import io.r2dbc.postgresql.PostgresqlConnectionFactory
import io.r2dbc.postgresql.client.SSLMode
import io.r2dbc.spi.ConnectionFactoryOptions
import io.r2dbc.spi.ConnectionFactoryOptions.DATABASE
import io.r2dbc.spi.ConnectionFactoryOptions.DRIVER
import io.r2dbc.spi.ConnectionFactoryOptions.HOST
import io.r2dbc.spi.ConnectionFactoryOptions.PASSWORD
import io.r2dbc.spi.ConnectionFactoryOptions.PORT
import io.r2dbc.spi.ConnectionFactoryOptions.SSL
import io.r2dbc.spi.ConnectionFactoryOptions.USER
import io.r2dbc.spi.Option
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
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.DefaultConfiguration
import java.io.File
import java.io.IOException
import java.util.*

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
        val sslKeyDer = environment.config.property("database.sslKeyPK8").getString()
        val sslKeyPem = "/tmp/sslkey-pk8.pem"
        derPkcs8ToPem(sslKeyDer, sslKeyPem)

        val sslMode = environment.config.property("database.sslMode").getString()
        val sslRootCert = environment.config.property("database.sslRootCert").getString()
        val jdbcUrl = environment.config.property("database.jdbcUrl").getString()

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

        val connectionConfig =
            PostgresqlConnectionConfiguration
                .builder()
                .host(host)
                .port(port)
                .username(user)
                .password(password)
                .sslRootCert(sslRootCert)
                .sslKey(sslKeyPem)
                .sslCert(sslCert)
                .sslMode(SSLMode.VERIFY_CA)
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

/**
 * Converts a PKCS#8 DER-encoded private key file to PEM format.
 * @param derPath Path to the input .der file (PKCS#8, binary)
 * @param pemPath Path to the output .pem file (PKCS#8, PEM)
 */
fun derPkcs8ToPem(
    derPath: String,
    pemPath: String,
) {
    val derBytes = File(derPath).readBytes()
    val base64 = Base64.getEncoder().encodeToString(derBytes)
    val pem =
        buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            base64.chunked(64).forEach { append(it).append('\n') }
            append("-----END PRIVATE KEY-----\n")
        }
    File(pemPath).writeText(pem)
}
