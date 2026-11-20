@file:Suppress("TooGenericExceptionCaught", "LongParameterList", "ReturnCount")

package no.nav.sosialhjelp.upload.files

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.UploadMetadata
import no.nav.sosialhjelp.upload.submission.SubmissionResolver
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries.SubmissionOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.upload.FilePipeline
import no.nav.sosialhjelp.upload.upload.PipelineResult
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.Upload
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.MAX_FILES_PER_SUBMISSION
import no.nav.sosialhjelp.upload.validation.MAX_TOTAL_SIZE_BYTES
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.Validation
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

sealed interface FileUploadResult {
    data class Stored(
        val upload: Upload,
        val submissionId: UUID,
        val replay: Boolean,
    ) : FileUploadResult

    data class Rejected(
        val codes: List<ValidationCode>,
    ) : FileUploadResult

    data class Failed(
        val stage: PipelineResult.Failed.Stage,
    ) : FileUploadResult

    data object Forbidden : FileUploadResult
}

private const val UNIQUE_VIOLATION = "23505"

/**
 * Synchronous upload: store the file in mellomlagring first, then insert the row.
 * A failure after storage leaves an orphan file (deleted on a later reconcile), never a row without a file.
 */
class FileUploadService(
    private val validator: UploadValidator,
    private val submissionResolver: SubmissionResolver,
    private val filePipeline: FilePipeline,
    private val fileUploadQueries: FileUploadQueries,
    private val uploadRepository: UploadRepository,
    private val mellomlagringClient: MellomlagringClient,
    private val dsl: DSLContext,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun upload(
        metadata: UploadMetadata,
        correlationId: UUID,
        data: ByteArray,
        personident: String,
        token: String,
    ): FileUploadResult {
        val filename = metadata.filename
        val early = validator.validate(filename, data.size.toLong())
        if (early.isNotEmpty()) return rejected(early)

        val resolved =
            try {
                submissionResolver.resolve(metadata, personident, token)
            } catch (_: SubmissionOwnedByAnotherUserException) {
                return FileUploadResult.Forbidden
            }

        val existing = findByCorrelationId(resolved.submissionId, correlationId)
        if (existing?.filId != null) return FileUploadResult.Stored(existing, resolved.submissionId, replay = true)

        limitViolation(resolved.submissionId, data.size.toLong())?.let { return FileUploadResult.Rejected(listOf(it)) }

        val uploadId = UUID.randomUUID()
        val stored =
            when (val result = filePipeline.run(uploadId, filename, data, resolved.navEksternRefId)) {
                is PipelineResult.Rejected -> return rejected(result.errors)
                is PipelineResult.Failed -> return FileUploadResult.Failed(result.stage)
                is PipelineResult.Stored -> result
            }

        try {
            withContext(ioDispatcher) {
                dsl.transaction { tx ->
                    fileUploadQueries.insertCompleted(
                        tx,
                        uploadId,
                        resolved.submissionId,
                        filename,
                        data.size.toLong(),
                        correlationId,
                        stored,
                    )
                }
            }
        } catch (e: Exception) {
            deleteStoredFile(resolved.navEksternRefId, stored.filId)
            if (e.isUniqueViolation()) {
                // A parallel retry with the same correlationId won the race.
                val winner = findByCorrelationId(resolved.submissionId, correlationId)
                if (winner?.filId != null) return FileUploadResult.Stored(winner, resolved.submissionId, replay = true)
            }
            throw e
        }

        val upload =
            findByCorrelationId(resolved.submissionId, correlationId) ?: error("Upload $uploadId missing after insert")
        return FileUploadResult.Stored(upload, resolved.submissionId, replay = false)
    }

    /**
     * Row first, then file: a failure in between leaves an orphan file (healed by reconcile),
     * never a row pointing at nothing. Idempotent: a missing row is a no-op.
     */
    suspend fun delete(uploadId: UUID) {
        val target =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    val upload =
                        runCatching { fileUploadQueries.getUpload(tx, uploadId) }.getOrNull()
                            ?: return@transactionResult null
                    fileUploadQueries.deleteUpload(tx, uploadId)
                    upload
                }
            } ?: return
        val filId = target.filId
        val navEksternRefId = target.navEksternRefId
        if (filId != null && navEksternRefId != null) deleteStoredFile(navEksternRefId, filId)
    }

    private fun rejected(errors: List<Validation>) = FileUploadResult.Rejected(errors.map { it.code }.distinct())

    private suspend fun findByCorrelationId(
        submissionId: UUID,
        correlationId: UUID,
    ): Upload? =
        withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                uploadRepository
                    .getUploads(tx, submissionId)
                    .firstOrNull { it.correlationId == correlationId && it.status == Status.COMPLETE }
            }
        }

    private suspend fun limitViolation(
        submissionId: UUID,
        newSize: Long,
    ): ValidationCode? {
        val complete =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploads(tx, submissionId).filter { it.status == Status.COMPLETE }
                }
            }
        return when {
            complete.size + 1 > MAX_FILES_PER_SUBMISSION -> ValidationCode.TOO_MANY_FILES
            complete.sumOf { it.mellomlagringStorrelse ?: 0L } + newSize > MAX_TOTAL_SIZE_BYTES ->
                ValidationCode.TOTAL_SIZE_TOO_LARGE
            else -> null
        }
    }

    private suspend fun deleteStoredFile(
        navEksternRefId: String,
        filId: UUID,
    ) {
        try {
            mellomlagringClient.deleteFile(navEksternRefId, filId, throwOnError = true)
        } catch (e: Exception) {
            logger.error("Could not delete file $filId after failed row insert; left as orphan", e)
            meterRegistry.counter("mellomlagring.orphaned_file").increment()
        }
    }

    private fun Throwable.isUniqueViolation(): Boolean =
        generateSequence(this) { it.cause }.any { (it is SQLException && it.sqlState == UNIQUE_VIOLATION) }
}
