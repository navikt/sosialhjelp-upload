package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.Fil
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.EttersendelsePdfGenerator
import no.nav.sosialhjelp.upload.pdf.PdfFil
import no.nav.sosialhjelp.upload.pdf.PdfMetadata
import org.jooq.DSLContext
import java.util.*
import kotlin.time.measureTimedValue
import kotlin.time.toJavaDuration

class EttersendelseService(
    private val fiksClient: FiksClient,
    private val dsl: DSLContext,
    private val submissionRepository: SubmissionRepository,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val encryptionService: EncryptionService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        val navEksternRefId = withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                submissionRepository.getNavEksternRefId(tx, submissionId, personIdent)
            }
        }

        val uploads = withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                uploadRepository.getUploads(tx, submissionId)
            }

        }
        val ettersendelsePdf = EttersendelsePdfGenerator.generate(PdfMetadata(metadata.type, uploads.mapNotNull { it.mellomlagringFilnavn?.let { filnavn -> PdfFil(filnavn) } }), personIdent)
        mellomlagringClient.uploadFile(navEksternRefId, "ettersendelse.pdf", "application/pdf", encryptionService.encryptBytes(ettersendelsePdf))

        val filer = uploads.mapNotNull {
            if (it.mellomlagringFilnavn == null || it.sha512 == null) {
                return@mapNotNull null
            }
            Fil(it.mellomlagringFilnavn, it.sha512)
        }
        val (response, duration) = measureTimedValue {
            fiksClient.uploadEttersendelse(
                fiksDigisosId,
                kommunenummer,
                navEksternRefId,
                metadata,
                token,
                filer,
            )
        }

        val result = if (response.status.isSuccess()) "success" else "failure"
        meterRegistry.timer("fiks.submission", "result", result).record(duration.toJavaDuration())

        if (response.status.isSuccess()) {
            withContext(ioDispatcher) {
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
