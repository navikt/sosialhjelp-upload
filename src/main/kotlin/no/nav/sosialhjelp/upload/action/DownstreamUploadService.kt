package no.nav.sosialhjelp.upload.action

import io.ktor.http.isSuccess
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.api.fiks.DigisosSak
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import org.jooq.DSLContext
import java.time.Duration
import java.util.UUID

private const val COUNTER_SUFFIX_LENGTH = 4

class DownstreamUploadService(
    private val fiksClient: FiksClient,
    private val dsl: DSLContext,
    private val submissionRepository: SubmissionRepository,
    private val uploadRepository: UploadRepository,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
) {
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

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
    ): Boolean {
        val uploads =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploadsWithFilenames(tx, submissionId).toList()
                }
            }

        val validUploads = uploads.filter { it.errors.isEmpty() && it.filId != null }

        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId = lagNavEksternRefId(sak)

        val filReferanser =
            validUploads.map { upload ->
                FilReferanse(
                    filnavn = upload.mellomlagringFilnavn ?: upload.originalFilename!!,
                    filId = upload.filId!!,
                    mellomlagringRefId = upload.navEksternRefId!!,
                    storrelse = upload.mellomlagringStorrelse ?: 0L,
                )
            }

        val startTime = System.nanoTime()
        val response =
            fiksClient.uploadEttersendelse(
                fiksDigisosId,
                kommunenummer,
                navEksternRefId,
                filReferanser,
                metadata,
                token,
            )
        val elapsed = Duration.ofNanos(System.nanoTime() - startTime)
        val result = if (response.status.isSuccess()) "success" else "failure"
        meterRegistry.timer("fiks.submission", "result", result).record(elapsed)

        if (response.status.isSuccess()) {
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    submissionRepository.cleanup(tx, submissionId)
                }
            }
            notificationService.notifyUpdate(submissionId)
            return true
        }
        return false
    }
}

data class FilReferanse(
    val filnavn: String,
    val filId: UUID,
    val mellomlagringRefId: String,
    val storrelse: Long,
)

@Serializable
data class VedleggSpesifikasjon(
    val type: String,
    val tilleggsinfo: String?,
    val innsendelsesfrist: String?,
    val hendelsetype: String?,
    val hendelsereferanse: String?,
)
