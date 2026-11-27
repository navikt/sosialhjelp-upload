@file:Suppress("LongParameterList", "TooGenericExceptionCaught")

package no.nav.sosialhjelp.upload.tus

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.files.FileUploadQueries
import no.nav.sosialhjelp.upload.submission.NavEksternRefIdLock
import no.nav.sosialhjelp.upload.submission.SubmissionResolver
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries.SubmissionOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.tus.storage.ChunkStorage
import no.nav.sosialhjelp.upload.upload.ChunkAssemblyService
import no.nav.sosialhjelp.upload.upload.UploadProcessingQueries
import no.nav.sosialhjelp.upload.upload.UploadProcessingService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

class TusUploadService(
    private val tusUploadQueries: TusUploadQueries,
    private val fileUploadQueries: FileUploadQueries,
    private val submissionResolver: SubmissionResolver,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val dsl: DSLContext,
    private val validator: UploadValidator,
    private val mellomlagringClient: MellomlagringClient,
    private val chunkStorage: ChunkStorage,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val uploadProcessingService: UploadProcessingService,
    private val processingScope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val lock: NavEksternRefIdLock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun create(
        metadata: TusMetadata,
        size: Long,
        personident: String,
        token: String,
    ): UUID {
        val filename = metadata.filename
        return try {
            val resolved = submissionResolver.resolve(metadata, personident, token)
            dsl.transactionCoroutine { tx ->
                val created =
                    tusUploadQueries.create(tx, resolved.submissionId, filename, size, metadata.correlationId)
                if (created == null) {
                    // Retried creation request: the upload already exists, so return it.
                    val correlationId = metadata.correlationId ?: error("Failed to create upload record")
                    return@transactionCoroutine tusUploadQueries.findIdByCorrelationId(
                        tx,
                        resolved.submissionId,
                        correlationId,
                    ) ?: error("Failed to create upload record")
                }
                meterRegistry.counter("upload.created").increment()
                val extension = File(filename).extension.lowercase().ifEmpty { "none" }
                meterRegistry.counter("upload.file_extension", "extension", extension).increment()
                val validations = validator.validate(filename, fileSize = size)
                if (validations.isNotEmpty()) {
                    uploadProcessingQueries.addErrors(tx, created, validations)
                }
                created
            }
        } catch (_: SubmissionOwnedByAnotherUserException) {
            throw UploadForbiddenException("Document is owned by another user")
        }
    }

    suspend fun getUploadInfo(uploadId: UUID): Pair<Long, Long> =
        withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                tusUploadQueries.getUploadInfo(tx, uploadId)
            }
        }

    suspend fun appendChunk(
        uploadId: UUID,
        expectedOffset: Long,
        data: ByteArray,
    ): Long {
        val chunkKey = "uploads/$uploadId-chunk-$expectedOffset"
        chunkStorage.writeChunk(chunkKey, data)

        val (totalSize, newOffset) =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    // Throws OffsetMismatchException if offset doesn't match (→ 409 in route)
                    tusUploadQueries.appendChunk(tx, uploadId, expectedOffset, data.size)
                }
            }
        meterRegistry.summary("upload.chunk.bytes").record(data.size.toDouble())

        if (newOffset == totalSize) {
            val claimed =
                withContext(ioDispatcher) {
                    dsl.transactionResult { tx -> tusUploadQueries.claimForProcessing(tx, uploadId) }
                }
            if (claimed) {
                logger.info("Upload $uploadId complete — launching background processing")
                processingScope.launch {
                    try {
                        uploadProcessingService.process(uploadId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Unhandled error processing upload $uploadId", e)
                    }
                }
            } else {
                logger.info("Upload $uploadId already claimed for processing, skipping")
            }
        }

        return newOffset
    }

    suspend fun delete(uploadId: UUID) {
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> fileUploadQueries.getUpload(tx, uploadId) }
            }
        val filId = upload.filId
        val navEksternRefId = upload.navEksternRefId

        withMdc("navEksternRefId" to navEksternRefId) {
            if (navEksternRefId == null) {
                deleteFileThenRow(uploadId, filId, null)
            } else {
                // Shared lock: a reconcile must not list between the file delete and the row delete.
                lock.shared(navEksternRefId) { deleteFileThenRow(uploadId, filId, navEksternRefId) }
            }
        }
    }

    private suspend fun deleteFileThenRow(
        uploadId: UUID,
        filId: UUID?,
        navEksternRefId: String?,
    ) {
        run {
            if (filId != null && navEksternRefId != null) {
                runCatching {
                    mellomlagringClient.deleteFile(navEksternRefId, filId, throwOnError = true)
                }.onFailure { e ->
                    throw MellomlagringDeleteException(
                        "Mellomlagring delete failed",
                        e,
                    )
                }
            }

            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> fileUploadQueries.deleteUpload(tx, uploadId) }
            }
            chunkAssemblyService.deleteGcsObjects(uploadId)
        }
    }

    class UploadForbiddenException(
        message: String,
    ) : RuntimeException(message)

    class MellomlagringDeleteException(
        message: String,
        cause: Throwable,
    ) : RuntimeException(message, cause)
}
