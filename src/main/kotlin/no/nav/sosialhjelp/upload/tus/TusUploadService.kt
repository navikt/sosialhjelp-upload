package no.nav.sosialhjelp.upload.tus

import io.ktor.http.ContentType
import io.ktor.http.defaultForFile
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.SubmissionRepository.SubmissionOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.storage.ChunkStorage
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

class TusUploadService(
    private val uploadRepository: UploadRepository,
    private val submissionRepository: SubmissionRepository,
    private val dsl: DSLContext,
    private val validator: UploadValidator,
    private val gotenbergService: GotenbergService,
    private val mellomlagringClient: MellomlagringClient,
    private val encryptionService: EncryptionService,
    private val chunkStorage: ChunkStorage,
    private val processingScope: CoroutineScope,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun create(
        contextId: String,
        filename: String,
        size: Long,
        personident: String,
    ): UUID {
        return try {
            dsl.transactionResult { tx ->
                val submissionId = submissionRepository.getSubmission(tx, contextId, personident)
                uploadRepository.create(tx, submissionId, filename, size)
                    ?: error("Failed to create upload record")
            }.also { meterRegistry.counter("upload.created").increment() }
        } catch (_: SubmissionOwnedByAnotherUserException) {
            throw UploadForbiddenException("Document is owned by another user")
        }
    }

    fun getUploadInfo(uploadId: UUID): Pair<Long, Long> =
        dsl.transactionResult { tx ->
            uploadRepository.getUploadInfo(tx, uploadId)
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
                    uploadRepository.appendChunk(tx, uploadId, expectedOffset, data.size)
                }
            }
        meterRegistry.summary("upload.chunk.bytes").record(data.size.toDouble())

        if (newOffset == totalSize) {
            val claimed = withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadRepository.claimForProcessing(tx, uploadId) }
            }
            if (claimed) {
                logger.info("Upload $uploadId complete — launching background processing")
                processingScope.launch {
                    runCatching { processCompletedUpload(uploadId) }
                        .onFailure { logger.error("Unhandled error processing upload $uploadId", it) }
                }
            } else {
                logger.info("Upload $uploadId already claimed for processing, skipping")
            }
        }

        return newOffset
    }

    private suspend fun processCompletedUpload(
        uploadId: UUID,
    ) {
        val startTime = System.nanoTime()
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploadForProcessing(tx, uploadId)
                }
            }

        val chunkData = withContext(ioDispatcher) {
            val chunkPrefix = "uploads/$uploadId-chunk-"
            val chunkKeys = chunkStorage.listKeys(chunkPrefix).sortedBy { key ->
                key.removePrefix(chunkPrefix).toLongOrNull() ?: 0L
            }
            if (chunkKeys.isEmpty()) {
                error("No chunk objects found for upload $uploadId at prefix $chunkPrefix")
            }
            val composedKey = upload.gcsKey
            chunkStorage.composeChunks(chunkKeys, composedKey)
            chunkStorage.readObject(composedKey)
        }

        val errors = validator.validate(upload.filename, chunkData, chunkData.size.toLong())
        if (errors.isNotEmpty()) {
            logger.info("Upload $uploadId failed validation: ${errors.map { it.code }}")
            withContext(ioDispatcher) {
                dsl.transaction { tx ->
                    uploadRepository.addErrors(tx, uploadId, errors)
                }
                deleteGcsObjects(uploadId)
            }
            meterRegistry.timer("upload.processing", "result", "validation_failure")
                .record(Duration.ofNanos(System.nanoTime() - startTime))
            return
        }

        val (finalFilename, finalData) = convertIfNeeded(upload.filename, chunkData)
        val encrypted = encryptionService.encryptBytes(finalData)

        val contentType =
            ContentType
                .defaultForFile(File(finalFilename))
                .toString()

        logger.info("Uploading $finalFilename (${encrypted.size} bytes) to mellomlagring for ${upload.navEksternRefId}")
        val filId =
            try {
                mellomlagringClient.uploadFile(
                    navEksternRefId = upload.navEksternRefId,
                    filename = finalFilename,
                    contentType = contentType,
                    data = encrypted,
                )
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during mellomlagring upload", e)
                withContext(ioDispatcher) {
                    dsl.transaction { tx ->
                        uploadRepository.markFailed(tx, uploadId)
                        uploadRepository.notifyChange(tx, uploadId)
                    }
                    deleteGcsObjects(uploadId)
                }
                meterRegistry.timer("upload.processing", "result", "mellomlagring_failure")
                    .record(Duration.ofNanos(System.nanoTime() - startTime))
                return
            }
        logger.info("Upload $uploadId stored in mellomlagring as $filId")

        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadRepository.setFilId(tx, uploadId, filId, finalFilename, finalData.size.toLong(), getSha512(finalData))
                uploadRepository.notifyChange(tx, uploadId)
            }
            deleteGcsObjects(uploadId)
        }
        meterRegistry.timer("upload.processing", "result", "success")
            .record(Duration.ofNanos(System.nanoTime() - startTime))
    }

    private suspend fun deleteGcsObjects(uploadId: UUID) {
        val chunkPrefix = "uploads/$uploadId-chunk-"
        runCatching {
            val chunkKeys = chunkStorage.listKeys(chunkPrefix)
            (chunkKeys + listOf("uploads/$uploadId")).forEach { key ->
                runCatching { chunkStorage.deleteObject(key) }
                    .onFailure { logger.warn("Failed to delete GCS object $key", it) }
            }
        }.onFailure { logger.warn("Failed to list GCS chunks for cleanup of upload $uploadId", it) }
    }

    private suspend fun convertIfNeeded(
        filename: String,
        data: ByteArray,
    ): Pair<String, ByteArray> {
        val extension = File(filename).extension.lowercase()
        if (extension in listOf("pdf", "jpeg", "jpg", "png")) {
            return filename to data
        }
        val pdfName = File(filename).nameWithoutExtension + ".pdf"
        val converted = gotenbergService.convertToPdf(data, extension)
        return pdfName to converted
    }

    suspend fun delete(
        uploadId: UUID,
    ) {
        val (filId, navEksternRefId) = withContext(ioDispatcher) {
            dsl.transactionResult { tx ->
                val upload = uploadRepository.getUpload(tx, uploadId)
                uploadRepository.deleteUpload(tx, uploadId)
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

        deleteGcsObjects(uploadId)
    }

    class UploadForbiddenException(
        message: String,
    ) : RuntimeException(message)
}


fun getSha512(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-512")
    md.update(data)
    val digest = md.digest()
    return digest.fold("") { str, it -> str + "%02x".format(it) }
}
