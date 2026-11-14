@file:Suppress("LongParameterList")

package no.nav.sosialhjelp.upload.action

import io.ktor.http.*
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseAlreadyExistsException
import no.nav.sosialhjelp.upload.action.fiks.EttersendelseMottaker
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
import no.nav.sosialhjelp.upload.upload.Upload
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.SubmissionValidationCode
import no.nav.sosialhjelp.upload.validation.SubmissionValidationException
import no.nav.sosialhjelp.upload.validation.validateSubmissionUploads
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.time.Clock
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
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun upload(
        metadata: Metadata,
        fiksDigisosId: String,
        token: String,
        submissionId: UUID,
        personIdent: String,
        klageId: UUID? = null,
    ): Boolean {
        val mottaker =
            klageId?.let { validateKlage(fiksDigisosId, it, token) }
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

            generateAndUploadPdf(metadata, uploads, navEksternRefId, personIdent, mottaker)

            val filer = buildFilerList(uploads)

            submitToFiks(
                metadata,
                fiksDigisosId,
                navEksternRefId,
                mottaker,
                token,
                filer,
                submissionId,
            )
        }
    }

    private suspend fun validateKlage(
        fiksDigisosId: String,
        klageId: UUID,
        token: String,
    ): EttersendelseMottaker.Klage {
        val klage =
            fiksClient.getKlager(fiksDigisosId, token).firstOrNull {
                it.klageId == klageId &&
                    it.digisosId?.toString()?.equals(fiksDigisosId, ignoreCase = true) == true
            } ?: throw KlageNotFoundException()
        if (klage.trukket == true) {
            throw SubmissionValidationException(listOf(SubmissionValidationCode.KLAGE_TRUKKET))
        }
        val sendt = klageSendtTidspunkt(klage)
        if (sendt != null && klageFristUtlopt(sendt, clock.instant())) {
            throw SubmissionValidationException(listOf(SubmissionValidationCode.KLAGE_ETTERSENDELSE_FRIST_UTLOPT))
        }
        return EttersendelseMottaker.Klage(klageId)
    }

    private suspend fun generateAndUploadPdf(
        metadata: Metadata,
        uploads: List<Upload>,
        navEksternRefId: String,
        personIdent: String,
        mottaker: EttersendelseMottaker,
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
                    EttersendelsePdfGenerator.generate(pdfMetadata, personIdent, mottaker)
                } catch (e: PdfGenerationException) {
                    logger.error("PDF generation failed, retrying with degraded filenames", e)
                    meterRegistry.counter("pdf.degraded_fallback").increment()
                    val degraded =
                        pdfMetadata.copy(
                            filer = List(pdfMetadata.filer.size) { idx -> PdfFil("Vedlegg ${idx + 1}") },
                        )
                    EttersendelsePdfGenerator.generate(degraded, personIdent, mottaker)
                }
            }
        mellomlagringClient.replaceFile(
            navEksternRefId,
            "ettersendelse.pdf",
            "application/pdf",
            encryptionService.encryptBytes(ettersendelsePdf),
        )
    }

    private fun buildFilerList(uploads: List<Upload>): List<Fil> =
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
