package no.nav.sosialhjelp.upload.action.fiks

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.plugins.di.annotations.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.Metadata
import no.nav.sosialhjelp.upload.action.Upload
import no.nav.sosialhjelp.upload.action.VedleggSpesifikasjon
import no.nav.sosialhjelp.upload.texas.TexasClient
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
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
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.BODY
            }
            install(ContentNegotiation) {
                json()
            }
        }
    }

    private val publicKeyClient by lazy {
        HttpClient(CIO) {
            install(Logging) {
                logger = Logger.DEFAULT
                level = LogLevel.BODY
            }
            install(ContentNegotiation) {
                json()
            }
        }
    }

    suspend fun fetchPublicKey(): X509Certificate {
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
                    }.bodyAsBytes()
                    .also {
                        logger.info("Hentet public key fra dokumentlager")
                    }
            }
        return try {
            val certificateFactory = CertificateFactory.getInstance("X.509")
            (certificateFactory.generateCertificate(ByteArrayInputStream(publicKey)) as X509Certificate)
        } catch (e: CertificateException) {
            throw IllegalStateException(e)
        }
    }

    suspend fun uploadEttersendelse(
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
        files: List<Upload>,
        metadata: Metadata,
        token: String,
    ): HttpResponse =
        client.submitFormWithBinaryData(
            ettersendelseUrl(fiksDigisosId, kommunenummer, navEksternRefId),
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
                files.forEachIndexed { index, file ->
                    val vedleggMetadata =
                        VedleggMetadata(
                            filnavn = file.filename,
                            mimetype = file.fileType,
                            storrelse = file.file.size.toLong(),
                        )
                    append(
                        "vedleggSpesifikasjon:$index",
                        Json.encodeToString(vedleggMetadata),
                        Headers.build {
                            append(HttpHeaders.ContentType, "text/plain;charset=UTF-8")
                        },
                    )
                    append(
                        "dokument:$index",
                        file.file,
                        Headers.build {
                            append(HttpHeaders.ContentType, ContentType.Application.OctetStream)
                            append(HttpHeaders.ContentDisposition, "filename=\"${file.filename}\"")
                        },
                    )
                }
            },
        ) {
            // TODO: Legg på integrasjonsid/-passord i header
            headers {
                integrasjonsid?.let { append("IntegrasjonId", integrasjonsid) }
                integrasjonspassord?.let { append("IntegrasjonPassord", integrasjonspassord) }
            }
            bearerAuth(token)
            contentType(ContentType.MultiPart.FormData)
        }

    suspend fun getSak(
        id: String,
        token: String,
    ): DigisosSak =
        jacksonClient
            .get(digisosSakUrl(id)) {
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

@Serializable
private data class VedleggMetadata(
    val filnavn: String?,
    val mimetype: String?,
    val storrelse: Long,
)
