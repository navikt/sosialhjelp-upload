package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.di.annotations.*
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Serializer
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.contentnegotiation.HendelseTypeSerializer
import no.nav.sosialhjelp.upload.texas.TexasClient
import org.slf4j.LoggerFactory
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class FiksClient(
    @Property("fiks.baseUrl") private val fiksBaseUrl: String,
    @Property("fiks.integrasjonsid") private val integrasjonsid: String?,
    @Property("fiks.integrasjonspassord") private val integrasjonspassord: String?,
    private val texasClient: TexasClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java.name)

    private fun ettersendelseUrl(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
    ) = "$fiksBaseUrl/digisos/api/v2/soknader/$kommunenummer/$fiksDigisosId/$navEksternRefId"

    private fun digisosSakUrl(fiksDigisosId: String) = "$fiksBaseUrl/digisos/api/v1/soknader/$fiksDigisosId"

    private val jacksonClient by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                jackson()
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) = this@FiksClient.logger.info(message)
                }
                level = LogLevel.INFO
            }
        }
    }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json()
            }
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) = this@FiksClient.logger.info(message)
                }
                level = LogLevel.INFO
            }
        }
    }

    private val certCacheTtlMs = 3_600_000L // 1 hour
    private val certCacheMutex = Mutex()

    @Volatile
    private var cachedCert: Pair<X509Certificate, Long>? = null

    suspend fun fetchPublicKey(): X509Certificate {
        val cached = cachedCert
        if (cached != null && System.currentTimeMillis() - cached.second < certCacheTtlMs) {
            return cached.first
        }
        return certCacheMutex.withLock {
            // Re-check inside lock — another coroutine may have refreshed while we waited
            val cachedNow = cachedCert
            if (cachedNow != null && System.currentTimeMillis() - cachedNow.second < certCacheTtlMs) {
                return@withLock cachedNow.first
            }
            val cert = fetchPublicKeyFromNetwork()
            cachedCert = cert to System.currentTimeMillis()
            cert
        }
    }

    private suspend fun fetchPublicKeyFromNetwork(): X509Certificate {
        val publicKey =
            withContext(ioDispatcher) {
                client
                    .get("$fiksBaseUrl/digisos/api/v1/dokumentlager-public-key") {
                        headers {
                            integrasjonsid?.let { append("IntegrasjonId", integrasjonsid) }
                            integrasjonspassord?.let { append("IntegrasjonPassord", integrasjonspassord) }
                        }
                        bearerAuth(texasClient.getMaskinportenToken())
                    }.apply {
                        if (!status.isSuccess()) {
                            logger.error("Feil ved henting av public key fra dokumentlager: $status")
                            throw Exception("Feil ved henting av public key fra dokumentlager: $status")
                        }
                    }.bodyAsChannel()
                    .also {
                        logger.info("Hentet public key fra dokumentlager")
                    }
            }
        return try {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            (certificateFactory.generateCertificate(publicKey.toInputStream())) as X509Certificate
        } catch (e: CertificateException) {
            throw IllegalStateException(e)
        }
    }

    suspend fun uploadEttersendelse(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
        metadata: Metadata,
        token: String,
        filer: List<Fil>,
    ): HttpResponse =
        withContext(ioDispatcher) {
            val vedleggJson =
                VedleggSpesifikasjon(
                    vedlegg = listOf(
                        Vedlegg(
                            type = metadata.type,
                            tilleggsinfo = metadata.tilleggsinfo,
                            hendelseType = metadata.hendelsetype?.let { Vedlegg.HendelseType.fromValue(it) },
                            hendelseReferanse = metadata.hendelsereferanse,
                            status = Vedlegg.Status.LastetOpp,
                            filer = filer,
                            klageId = null
                        )
                    )
                )
            val formData =
                formData {
                    append(
                        "vedlegg.json",
                        Json.encodeToString(vedleggJson),
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        },
                    )
                }
            try {
                client
                    .submitFormWithBinaryData(
                        ettersendelseUrl(fiksDigisosId, kommunenummer, navEksternRefId),
                        formData,
                    ) {
                        headers {
                            integrasjonsid?.let { append("IntegrasjonId", integrasjonsid) }
                            integrasjonspassord?.let { append("IntegrasjonPassord", integrasjonspassord) }
                        }
                        bearerAuth(token)
                        contentType(ContentType.MultiPart.FormData)
                    }.also {
                        if (!it.status.isSuccess()) {
                            logger.error("Feil ved opplasting til fiks: ${it.status}: ${it.bodyAsText()}")
                        } else {
                            logger.info("Opplasting til fiks vellykket: ${it.status}")
                        }
                    }
            } catch (e: Exception) {
                logger.error("Feil ved opplasting til fiks: ${e.message}", e)
                throw e
            }
        }

    suspend fun getSak(
        id: String,
        token: String,
    ): DigisosSak =
        withContext(ioDispatcher) {
            jacksonClient
                .get(digisosSakUrl(id))
                {
                    headers {
                        integrasjonsid?.let {
                            append("IntegrasjonId", integrasjonsid)
                        }
                        integrasjonspassord?.let { append("IntegrasjonPassord", integrasjonspassord) }
                    }
                    accept(ContentType.Application.Json)
                    bearerAuth(token)
                }.body()
        }

    suspend fun getNewNavEksternRefId(fiksDigisosId: String, token: String, localMax: String? = null): String {
        val digisosSak = getSak(fiksDigisosId, token)
        return lagNavEksternRefId(digisosSak, localMax)
    }
}

private const val COUNTER_SUFFIX_LENGTH = 4

internal fun lagNavEksternRefId(digisosSak: DigisosSak, localMax: String? = null): String {
    val remoteMax: String? =
        digisosSak.ettersendtInfoNAV
            ?.ettersendelser
            ?.map { it.navEksternRefId }
            ?.maxByOrNull { it.takeLast(COUNTER_SUFFIX_LENGTH).toLongOrNull() ?: 0L }

    // Take the highest counter across remote Fiks state and local in-flight submissions.
    // This ensures concurrent submissions that haven't been submitted to Fiks yet (and
    // therefore don't appear in ettersendtInfoNAV.ettersendelser) are still accounted for.
    val previousId: String =
        listOfNotNull(remoteMax, localMax)
            .maxByOrNull { it.takeLast(COUNTER_SUFFIX_LENGTH).toLong() }
            ?: digisosSak.originalSoknadNAV?.navEksternRefId?.plus("0000")
            ?: digisosSak.fiksDigisosId.plus("0000")

    val nesteSuffix = lagIdSuffix(previousId)
    return previousId.dropLast(COUNTER_SUFFIX_LENGTH).plus(nesteSuffix)
}

internal fun lagIdSuffix(previousId: String): String {
    val suffix = (previousId.takeLast(COUNTER_SUFFIX_LENGTH).toLongOrNull() ?: 0L) + 1
    return suffix.toString().padStart(4, '0')
}


@Serializable
data class Fil(val filnavn: String, val sha512: String)

// AKA metadata
@Serializable
data class Vedlegg(
    val type: String,
    val tilleggsinfo: String,
    val klageId: String? = null,
    val status: Status?,
    val filer: List<Fil>,
    @Serializable(with = HendelseTypeSerializer::class) val hendelseType: HendelseType?,
    val hendelseReferanse: String?,
) {
    enum class Status {
        LastetOpp, VedleggKreves, VedleggAlleredeSendt
    }

    enum class HendelseType(val value: String) {
        DOKUMENTASJON_ETTERSPURT("dokumentasjonEtterspurt"),
        DOKUMENTASJONKRAV("dokumentasjonkrav"),
        SOKNAD("soknad"),
        BRUKER("bruker");

        companion object {
            fun fromValue(value: String): HendelseType =
                when (value) {
                    "dokumentasjonEtterspurt" -> DOKUMENTASJON_ETTERSPURT
                    "dokumentasjonkrav" -> DOKUMENTASJONKRAV
                    "soknad" -> SOKNAD
                    "bruker" -> BRUKER
                    else -> error("Unknown hendelse type: $value")
                }
        }
    }
}

@Serializable
data class VedleggSpesifikasjon(
    val vedlegg: List<Vedlegg>,
)
