@file:Suppress("LongParameterList")

package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseAlreadyExistsException
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.Fil
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.pdf.EttersendelsePdfGenerator
import no.nav.sosialhjelp.upload.pdf.PdfFil
import no.nav.sosialhjelp.upload.pdf.PdfGenerationException
import no.nav.sosialhjelp.upload.pdf.PdfMetadata
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.SubmissionValidationException
import no.nav.sosialhjelp.upload.validation.validateSubmissionUploads
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.measureTimedValue
import kotlin.time.toJavaDuration

class EttersendelseService(
    private val fiksClient: FiksClient,
    private val dsl: DSLContext,
    private val ettersendelseSubmissionQueries: EttersendelseSubmissionQueries,
    private val submissionQueries: SubmissionQueries,
    private val notificationService: SubmissionNotificationService,
    private val meterRegistry: MeterRegistry,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val encryptionService: EncryptionService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
        personIdent: String,
    ): Boolean {
        val sak = fiksClient.getSak(fiksDigisosId, token)
        val kommunenummer = sak.kommunenummer
        val navEksternRefId =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    ettersendelseSubmissionQueries.getNavEksternRefId(tx, submissionId, personIdent)
                }
            }
        return withMdc("navEksternRefId" to navEksternRefId) {
            val uploads =
                withContext(ioDispatcher) {
                    dsl.transactionResult { tx -> uploadRepository.getUploads(tx, submissionId) }
                }

            val violations = validateSubmissionUploads(uploads)
            if (violations.isNotEmpty()) throw SubmissionValidationException(violations)

            generateAndUploadPdf(metadata, uploads, navEksternRefId, personIdent)

            val filer = buildFilerList(uploads)

            submitToFiks(metadata, fiksDigisosId, kommunenummer, navEksternRefId, token, filer, submissionId)
        }
    }

    private suspend fun generateAndUploadPdf(
        metadata: Metadata,
        uploads: List<no.nav.sosialhjelp.upload.upload.Upload>,
        navEksternRefId: String,
        personIdent: String,
    ) {
        val ettersendelsePdf =
            withContext(cpuDispatcher) {
                val pdfMetadata =
                    PdfMetadata(
                        metadata.type,
                        uploads.mapNotNull {
                            it.mellomlagringFilnavn?.let { filnavn ->
                                PdfFil(filnavn.replace(Regex("[\r\n\t]"), " ").trim())
                            }
                        },
                    )
                try {
                    EttersendelsePdfGenerator.generate(pdfMetadata, personIdent)
                } catch (e: PdfGenerationException) {
                    logger.error("PDF generation failed, retrying with degraded filenames", e)
                    meterRegistry.counter("pdf.degraded_fallback").increment()
                    val degraded =
                        pdfMetadata.copy(
                            filer = pdfMetadata.filer.mapIndexed { idx, _ -> PdfFil("Vedlegg ${idx + 1}") },
                        )
                    EttersendelsePdfGenerator.generate(degraded, personIdent)
                }
            }
        mellomlagringClient.replaceFile(
            navEksternRefId,
            "ettersendelse.pdf",
            "application/pdf",
            encryptionService.encryptBytes(ettersendelsePdf),
        )
    }

    private fun buildFilerList(uploads: List<no.nav.sosialhjelp.upload.upload.Upload>): List<Fil> =
        uploads.mapNotNull { upload ->
            if (upload.mellomlagringFilnavn == null || upload.sha512 == null) return@mapNotNull null
            Fil(upload.mellomlagringFilnavn, upload.sha512)
        }

    private suspend fun submitToFiks(
        metadata: Metadata,
        fiksDigisosId: String,
        kommunenummer: String,
        navEksternRefId: String,
        token: String,
        filer: List<Fil>,
        submissionId: UUID,
    ): Boolean {
        val (response, duration) =
            try {
                measureTimedValue {
                    fiksClient.uploadEttersendelse(
                        fiksDigisosId,
                        kommunenummer,
                        navEksternRefId,
                        metadata,
                        token,
                        filer,
                    )
                }
            } catch (e: EttersendelseAlreadyExistsException) {
                logger.warn(
                    "Ettersendelse ${e.navEksternRefId} already exists in Fiks for $fiksDigisosId " +
                        "— treating as success",
                )
                meterRegistry.counter("fiks.submission.already_exists").increment()
                cleanupSubmission(submissionId)
                return true
            }

        val result = if (response.status.isSuccess()) "success" else "failure"
        meterRegistry.timer("fiks.submission", "result", result).record(duration.toJavaDuration())

        if (response.status.isSuccess()) {
            cleanupSubmission(submissionId)
            logger.info("Successfully submitted ettersendelse for $fiksDigisosId to Fiks")
            return true
        }
        return false
    }

    private suspend fun cleanupSubmission(submissionId: UUID) {
        withContext(ioDispatcher) {
            dsl.transactionResult { tx -> submissionQueries.cleanup(tx, submissionId) }
        }
        notificationService.notifyDeleted(submissionId)
    }
}
