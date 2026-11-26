@file:Suppress("TooGenericExceptionCaught", "LongParameterList", "ReturnCount")

package no.nav.sosialhjelp.upload.files

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.UploadMetadata
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
import no.nav.sosialhjelp.upload.submission.ResolvedSubmission
import no.nav.sosialhjelp.upload.submission.SubmissionResolver
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries.SubmissionOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.upload.FilePipeline
import no.nav.sosialhjelp.upload.upload.PipelineResult
import no.nav.sosialhjelp.upload.upload.PrepareResult
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
    private val lock: NavEksternRefIdLock,
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
        val earlyValidation = validator.validate(filename, data.size.toLong())
        if (earlyValidation.isNotEmpty()) return rejected(earlyValidation)

        val resolved =
            try {
                submissionResolver.resolve(metadata, personident, token)
            } catch (_: SubmissionOwnedByAnotherUserException) {
                return FileUploadResult.Forbidden
            }

        val existing = findByCorrelationId(resolved.submissionId, correlationId)
        if (existing?.filId != null) return FileUploadResult.Stored(existing, resolved.submissionId, replay = true)

        val complete =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploads(tx, resolved.submissionId).filter { it.status == Status.COMPLETE }
                }
            }

        if (complete.size + 1 > MAX_FILES_PER_SUBMISSION) {
            return FileUploadResult.Rejected(listOf(ValidationCode.TOO_MANY_FILES))
        }

        val uploadId = UUID.randomUUID()
        val prepared =
            when (val result = filePipeline.prepare(uploadId, filename, data)) {
                is PrepareResult.Rejected -> return rejected(result.errors)
                is PrepareResult.Failed -> return FileUploadResult.Failed(result.stage)
                is PrepareResult.Ready -> result
            }

        val newStoredSize = prepared.data.size.toLong()

        if (complete.sumOf { it.mellomlagringStorrelse ?: 0L } + newStoredSize > MAX_TOTAL_SIZE_BYTES) {
            return FileUploadResult.Rejected(listOf(ValidationCode.TOTAL_SIZE_TOO_LARGE))
        }

        // Shared lock only from storage until the row commits: reconcile (exclusive) can then never see
        // the stored file without its row and delete it as an orphan, while slow ClamAV/Gotenberg work
        // runs unlocked without holding a lock-pool connection.
        return lock.shared(resolved.navEksternRefId) {
            storeAndInsert(uploadId, filename, data.size.toLong(), correlationId, prepared, resolved)
        }
    }

    private suspend fun storeAndInsert(
        uploadId: UUID,
        filename: String,
        originalSize: Long,
        correlationId: UUID,
        prepared: PrepareResult.Ready,
        resolved: ResolvedSubmission,
    ): FileUploadResult {
        val stored =
            when (val result = filePipeline.store(prepared, uploadId, resolved.navEksternRefId)) {
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
                        originalSize,
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
        // Read navEksternRefId outside the lock (it is write-once), then delete row + file under the
        // shared lock so a reconcile never lists the file after its row is gone.
        val navEksternRefId =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> fileUploadQueries.findUpload(tx, uploadId) }
            }?.navEksternRefId
        if (navEksternRefId == null) {
            deleteRowAndFile(uploadId)
            return
        }
        lock.shared(navEksternRefId) { deleteRowAndFile(uploadId) }
    }

    private suspend fun deleteRowAndFile(uploadId: UUID) {
        val target =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    val upload =
                        fileUploadQueries.findUpload(tx, uploadId) ?: return@transactionResult null
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

    private suspend fun deleteStoredFile(
        navEksternRefId: String,
        filId: UUID,
    ) {
        try {
            mellomlagringClient.deleteFile(navEksternRefId, filId, throwOnError = true)
        } catch (e: Exception) {
            logger.error("Could not delete file $filId after failed upload; left as orphan", e)
            meterRegistry.counter("mellomlagring.orphaned_file").increment()
        }
    }

    private fun Throwable.isUniqueViolation(): Boolean =
        generateSequence(this) { it.cause }.any { (it is SQLException && it.sqlState == UNIQUE_VIOLATION) }
}
