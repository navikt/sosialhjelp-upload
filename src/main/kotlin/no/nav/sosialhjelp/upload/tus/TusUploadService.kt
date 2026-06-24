package no.nav.sosialhjelp.upload.tus

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
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
    private val tusSubmissionQueries: TusSubmissionQueries,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val dsl: DSLContext,
    private val validator: UploadValidator,
    private val fiksClient: FiksClient,
    private val mellomlagringClient: MellomlagringClient,
    private val chunkStorage: ChunkStorage,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val uploadProcessingService: UploadProcessingService,
    private val processingScope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun create(
        contextId: String,
        filename: String,
        size: Long,
        personident: String,
        token: String,
        fiksDigisosId: String?,
        navEksternRefId: String?,
        kategori: String? = null,
    ): UUID {
        return try {
            dsl.transactionCoroutine { tx ->
                // Acquire a per-fiksDigisosId advisory lock before deriving navEksternRefId.
                // This serializes concurrent upload creations for the same case across all
                // service instances, preventing two submissions from reading the same Fiks
                // state and deriving the same navEksternRefId.
                if (fiksDigisosId != null) {
                    tusSubmissionQueries.acquireAdvisoryLock(tx, fiksDigisosId)
                }

                val submissionId = tusSubmissionQueries.getOrCreateSubmission(tx, contextId, personident, fiksDigisosId, kategori)

                val eksternRef =
                    // If navEksternRefId is already set on this submission (e.g. a second
                    // upload on the same contextId), reuse it without calling Fiks.
                    tusSubmissionQueries.getNavEksternRefIdByContextId(tx, contextId)
                        ?: navEksternRefId
                        ?: fiksDigisosId?.let {
                            // Read the highest navEksternRefId stored locally for this
                            // fiksDigisosId. This accounts for other in-flight submissions
                            // that have been created locally but not yet submitted to Fiks,
                            // and therefore don't appear in ettersendtInfoNAV.ettersendelser.
                            val localMax = tusSubmissionQueries.getMaxNavEksternRefIdForFiksDigisosId(tx, it)
                            // We intentionally call Fiks inside a DB transaction to keep the
                            // lock → read-from-Fiks → write sequence atomic. The risk of
                            // holding a DB connection across a network call is accepted here
                            // because contention on the same fiksDigisosId is expected to be
                            // near zero in practice (fiksDigisosId is unique per application).
                            fiksClient.getNewNavEksternRefId(it, token, localMax)
                        }
                        ?: error("Verken navEksternRefId eller fiksDigisosId tilgjengelig")

                tusSubmissionQueries.setNavEksternRefId(tx, submissionId, eksternRef)
                val uploadId = tusUploadQueries
                    .create(tx, submissionId, filename, size)
                    .also {
                        meterRegistry.counter("upload.created").increment()
                        val extension = File(filename).extension.lowercase().ifEmpty { "none" }
                        meterRegistry.counter("upload.file_extension", "extension", extension).increment()
                    }
                    ?: error("Failed to create upload record")
                val validations = validator.validate(filename, fileSize = size)
                if (validations.isNotEmpty()) {
                    uploadProcessingQueries.addErrors(tx, uploadId, validations)
                }
                uploadId
            }
        } catch (_: SubmissionOwnedByAnotherUserException) {
            throw UploadForbiddenException("Document is owned by another user")
        }
    }

    fun getUploadInfo(uploadId: UUID): Pair<Long, Long> =
        dsl.transactionResult { tx ->
            tusUploadQueries.getUploadInfo(tx, uploadId)
        }

    suspend fun appendChunk(
        uploadId: UUID,
        expectedOffset: Long,
        data: ByteArray,
    ): Long {
        val chunkKey = "uploads/$uploadId-chunk-$expectedOffset"
        withContext(ioDispatcher) { chunkStorage.writeChunk(chunkKey, data) }

        val (totalSize, newOffset) =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    // Throws OffsetMismatchException if offset doesn't match (→ 409 in route)
                    tusUploadQueries.appendChunk(tx, uploadId, expectedOffset, data.size)
                }
            }
        meterRegistry.summary("upload.chunk.bytes").record(data.size.toDouble())

        if (newOffset == totalSize) {
            val claimed = withContext(ioDispatcher) {
                dsl.transactionResult { tx -> tusUploadQueries.claimForProcessing(tx, uploadId) }
            }
            if (claimed) {
                logger.info("Upload $uploadId complete — launching background processing")
                processingScope.launch {
                    runCatching { uploadProcessingService.process(uploadId) }
                        .onFailure { logger.error("Unhandled error processing upload $uploadId", it) }
                }
            } else {
                logger.info("Upload $uploadId already claimed for processing, skipping")
            }
        }

        return newOffset
    }

    suspend fun delete(uploadId: UUID) {
        val (filId, navEksternRefId) = withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                val upload = tusUploadQueries.getUpload(tx, uploadId)
                tusUploadQueries.deleteUpload(tx, uploadId)
                upload.filId to upload.navEksternRefId
            }
        }

        if (filId != null && navEksternRefId != null) {
            runCatching {
                mellomlagringClient.deleteFile(navEksternRefId, filId)
            }.onFailure {
                logger.warn("Failed to delete file $filId from mellomlagring after upload deletion; it may be orphaned", it)
            }
        }

        chunkAssemblyService.deleteGcsObjects(uploadId)
    }

    class UploadForbiddenException(message: String) : RuntimeException(message)
}
