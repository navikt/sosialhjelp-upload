@file:Suppress("LongParameterList")

package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseAlreadyExistsException
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseMottaker
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.Fil
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.common.SubmitInProgressException
import no.nav.sosialhjelp.upload.common.teamLogger
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
    private val teamLogger = teamLogger(this::class)

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
        personIdent: String,
        klageId: UUID? = null,
    ): Boolean {
        val attemptId = UUID.randomUUID()
        val claimed =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> submissionQueries.claimSubmit(tx, submissionId, attemptId) }
            }
        if (!claimed) {
            logger.warn("Submit rejected, another submit holds the lock for submission $submissionId")
            meterRegistry.counter("fiks.submission.rejected_in_progress").increment()
            throw SubmitInProgressException("Submit already in progress for submission $submissionId")
        }
        logger.info("Submit started for submission $submissionId, attempt $attemptId")

        var succeeded = false
        try {
            return doUpload(metadata, fiksDigisosId, token, submissionId, attemptId, personIdent, klageId)
                .also { succeeded = it }
        } finally {
            if (!succeeded) releaseSubmit(submissionId, attemptId)
        }
    }

    private suspend fun doUpload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
        attemptId: UUID,
        personIdent: String,
        klageId: UUID?,
    ): Boolean {
        // Klage-endepunktet trenger ikke kommunenummer, så vi henter bare saken for søknad.
        val mottaker =
            klageId?.let { EttersendelseMottaker.Klage(it) }
                ?: EttersendelseMottaker.Soknad(fiksClient.getSak(fiksDigisosId, token).kommunenummer)
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

            ensureCurrentAttempt(submissionId, attemptId)
            generateAndUploadPdf(metadata, uploads, navEksternRefId, personIdent)

            val filer = buildFilerList(uploads)

            ensureCurrentAttempt(submissionId, attemptId)
            submitToFiks(
                metadata,
                fiksDigisosId,
                navEksternRefId,
                mottaker,
                token,
                filer,
                submissionId,
                attemptId,
            )
        }
    }

    /** Fencing: stop if this attempt's lock expired and another attempt took over. */
    private suspend fun ensureCurrentAttempt(
        submissionId: UUID,
        attemptId: UUID,
    ) {
        val current =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> submissionQueries.isCurrentAttempt(tx, submissionId, attemptId) }
            }
        if (!current) {
            logger.warn("Submit attempt $attemptId for submission $submissionId lost its lock, aborting")
            throw SubmitInProgressException("Submit attempt $attemptId no longer holds the lock")
        }
    }

    private suspend fun releaseSubmit(
        submissionId: UUID,
        attemptId: UUID,
    ) {
        runCatching {
            withContext(NonCancellable + ioDispatcher) {
                dsl.transactionResult { tx -> submissionQueries.releaseSubmit(tx, submissionId, attemptId) }
            }
        }.onFailure { logger.error("Failed to release submit lock for submission $submissionId", it) }
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
        val pdfFilId =
            mellomlagringClient.replaceFile(
                navEksternRefId,
                "ettersendelse.pdf",
                "application/pdf",
                encryptionService.encryptBytes(ettersendelsePdf),
            )
        logger.info("Uploaded ettersendelse.pdf to mellomlagring as $pdfFilId")
    }

    private fun buildFilerList(uploads: List<no.nav.sosialhjelp.upload.upload.Upload>): List<Fil> =
        uploads.mapNotNull { upload ->
            if (upload.mellomlagringFilnavn == null || upload.sha512 == null) return@mapNotNull null
            Fil(upload.mellomlagringFilnavn, upload.sha512)
        }

    private suspend fun submitToFiks(
        metadata: Metadata,
        fiksDigisosId: String,
        navEksternRefId: String,
        mottaker: EttersendelseMottaker,
        token: String,
        filer: List<Fil>,
        submissionId: UUID,
        attemptId: UUID,
    ): Boolean {
        val (response, duration) =
            try {
                measureTimedValue {
                    fiksClient.uploadEttersendelse(
                        fiksDigisosId,
                        navEksternRefId,
                        mottaker,
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
                cleanupSubmission(submissionId, attemptId)
                return true
            }

        val result = if (response.status.isSuccess()) "success" else "failure"
        meterRegistry.timer("fiks.submission", "result", result).record(duration.toJavaDuration())

        if (response.status.isSuccess()) {
            cleanupSubmission(submissionId, attemptId)
            logger.info("Successfully submitted ettersendelse for $fiksDigisosId to Fiks")
            return true
        }
        return false
    }

    private suspend fun cleanupSubmission(
        submissionId: UUID,
        attemptId: UUID,
    ) {
        val deleted =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> submissionQueries.deleteAfterSubmit(tx, submissionId, attemptId) }
            }
        if (deleted > 0) {
            notificationService.notifyDeleted(submissionId)
        } else {
            logger.warn(
                "Submission $submissionId was not deleted after submit, attempt $attemptId no longer holds the lock",
            )
        }
    }
}
