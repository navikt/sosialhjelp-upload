package no.nav.sosialhjelp.upload.action

import io.ktor.http.isSuccess
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import org.jooq.DSLContext
import java.util.UUID
import kotlin.time.measureTimedValue
import kotlin.time.toJavaDuration

class DownstreamUploadService(
    private val fiksClient: FiksClient,
    private val dsl: DSLContext,
    private val submissionRepository: SubmissionRepository,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
) {
    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
        personIdent: String,
    ): Boolean {
        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer

        val navEksternRefId = withContext(Dispatchers.IO) {
            dsl.transactionResult { tx ->
                submissionRepository.getNavEksternRefId(tx, submissionId, personIdent)
            }
        }

        val (response, duration) = measureTimedValue {
            fiksClient.uploadEttersendelse(
                fiksDigisosId,
                kommunenummer,
                navEksternRefId,
                metadata,
                token,
            )
        }

        val result = if (response.status.isSuccess()) "success" else "failure"
        meterRegistry.timer("fiks.submission", "result", result).record(duration.toJavaDuration())

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

@Serializable
data class VedleggSpesifikasjon(
    val type: String,
    val tilleggsinfo: String?,
    val innsendelsesfrist: String?,
    val hendelsetype: String?,
    val hendelsereferanse: String?,
)
