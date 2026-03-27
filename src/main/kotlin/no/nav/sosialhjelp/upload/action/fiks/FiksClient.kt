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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.FilReferanse
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.action.VedleggSpesifikasjon
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
) {
    private val logger = LoggerFactory.getLogger(this::class.java.name)

    private fun ettersendelseUrl(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
    ) = "$fiksBaseUrl/digisos/api/v1/soknader/$kommunenummer/$fiksDigisosId/$navEksternRefId"

    private fun digisosSakUrl(fiksDigisosId: String) = "$fiksBaseUrl/digisos/api/v1/soknader/$fiksDigisosId"

    private val jacksonClient by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                jackson()
            }
        }
    }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
            install(ContentNegotiation) {
                json()
            }
        }
    }

    private val certCacheTtlMs = 3_600_000L // 1 hour

    @Volatile
    private var cachedCert: Pair<X509Certificate, Long>? = null

    suspend fun fetchPublicKey(): X509Certificate {
        val cached = cachedCert
        if (cached != null && System.currentTimeMillis() - cached.second < certCacheTtlMs) {
            return cached.first
        }
        val cert = fetchPublicKeyFromNetwork()
        cachedCert = cert to System.currentTimeMillis()
        return cert
    }

    private suspend fun fetchPublicKeyFromNetwork(): X509Certificate {
        val publicKey =
            withContext(Dispatchers.IO) {
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
        filReferanser: List<FilReferanse>,
        metadata: Metadata,
        token: String,
    ): HttpResponse =
        withContext(Dispatchers.IO) {
            val formData =
                formData {
                    val vedleggJson =
                        VedleggSpesifikasjon(
                            type = metadata.type,
                            tilleggsinfo = metadata.tilleggsinfo,
                            innsendelsesfrist = metadata.innsendelsesfrist,
                            hendelsetype = metadata.hendelsetype,
                            hendelsereferanse = metadata.hendelsereferanse,
                        )
                    append(
                        "vedlegg.json",
                        Json.encodeToString(vedleggJson),
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        },
                    )
                    filReferanser.forEachIndexed { index, ref ->
                        val vedleggMetadata =
                            VedleggMetadata(
                                filnavn = ref.filnavn,
                                filId = ref.filId.toString(),
                                mellomlagringRefId = ref.mellomlagringRefId,
                                storrelse = ref.storrelse,
                                mimetype = ref.mimeType
                            )
                        append(
                            "vedleggSpesifikasjon:$index",
                            Json.encodeToString(vedleggMetadata),
                            Headers.build {
                                append(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                            },
                        )
                    }
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
        withContext(Dispatchers.IO) {
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

    suspend fun getNewNavEksternRefId(fiksDigisosId: String, token: String): String {
        val digisosSak = getSak(fiksDigisosId, token)
        return lagNavEksternRefId(digisosSak)
    }
}

private const val COUNTER_SUFFIX_LENGTH = 4

private fun lagNavEksternRefId(digisosSak: DigisosSak): String {
    val previousId: String =
        digisosSak.ettersendtInfoNAV
            ?.ettersendelser
            ?.map { it.navEksternRefId }
            ?.maxByOrNull { it.takeLast(COUNTER_SUFFIX_LENGTH).toLong() }
            ?: digisosSak.originalSoknadNAV?.navEksternRefId?.plus("0000")
            ?: digisosSak.fiksDigisosId.plus("0000")

    val nesteSuffix = lagIdSuffix(previousId)
    return (previousId.dropLast(COUNTER_SUFFIX_LENGTH).plus(nesteSuffix))
}

private fun lagIdSuffix(previousId: String): String {
    val suffix = previousId.takeLast(COUNTER_SUFFIX_LENGTH).toLong() + 1
    return suffix.toString().padStart(4, '0')
}

@Serializable
private data class VedleggMetadata(
    val filnavn: String?,
    val filId: String?,
    val mellomlagringRefId: String?,
    val storrelse: Long,
    val mimetype: String?,
)
