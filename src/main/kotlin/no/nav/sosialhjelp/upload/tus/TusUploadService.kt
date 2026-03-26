package no.nav.sosialhjelp.upload.tus

import io.ktor.http.ContentType
import io.ktor.http.defaultForFile
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.FiksClient
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.SubmissionRepository.SubmissionOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
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
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun create(
        contextId: String,
        filename: String,
        size: Long,
        personident: String,
    ): UUID {
        return try {
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    val submissionId = submissionRepository.getSubmission(tx, contextId, personident)
                    uploadRepository.create(tx, submissionId, filename, size)
                        ?: error("Failed to create upload record")
                }
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
        userToken: String,
    ): Long {
        val (totalSize, newOffset) =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    // Throws OffsetMismatchException if offset doesn't match (→ 409 in route)
                    uploadRepository.appendChunk(tx, uploadId, expectedOffset, data)
                }
            }
        meterRegistry.summary("upload.chunk.bytes").record(data.size.toDouble())

        if (newOffset == totalSize) {
            // Atomically claim the upload for processing to prevent double-processing
            // on concurrent final-chunk delivery or client retries
            val claimed = withContext(Dispatchers.IO) {
                dsl.transactionResult { tx -> uploadRepository.claimForProcessing(tx, uploadId) }
            }
            if (claimed) {
                logger.info("Upload $uploadId complete, starting post-processing")
                processCompletedUpload(uploadId, userToken)
            } else {
                logger.info("Upload $uploadId already claimed for processing, skipping")
            }
        }

        return newOffset
    }

    private suspend fun processCompletedUpload(
        uploadId: UUID,
        userToken: String,
    ) {
        val startTime = System.nanoTime()
        val upload =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploadForProcessing(tx, uploadId)
                }
            }

        val errors = validator.validate(upload.filename, upload.chunkData, upload.chunkData.size.toLong())
        if (errors.isNotEmpty()) {
            logger.info("Upload $uploadId failed validation: ${errors.map { it.code }}")
            withContext(Dispatchers.IO) {
                dsl.transaction { tx ->
                    uploadRepository.addErrors(tx, uploadId, errors)
                    uploadRepository.clearChunkData(tx, uploadId)
                }
            }
            meterRegistry.timer("upload.processing", "result", "validation_failure")
                .record(Duration.ofNanos(System.nanoTime() - startTime))
            return
        }

        val (finalFilename, finalData) = convertIfNeeded(upload.filename, upload.chunkData)
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
                    token = userToken,
                )
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during mellomlagring upload", e)
                withContext(Dispatchers.IO) {
                    dsl.transaction { tx ->
                        uploadRepository.markFailed(tx, uploadId)
                        uploadRepository.clearChunkData(tx, uploadId)
                        uploadRepository.notifyChange(tx, uploadId)
                    }
                }
                meterRegistry.timer("upload.processing", "result", "mellomlagring_failure")
                    .record(Duration.ofNanos(System.nanoTime() - startTime))
                return
            }
        logger.info("Upload $uploadId stored in mellomlagring as $filId")

        withContext(Dispatchers.IO) {
            dsl.transaction { tx ->
                uploadRepository.setFilId(tx, uploadId, filId, finalFilename, encrypted.size.toLong())
                uploadRepository.clearChunkData(tx, uploadId)
                uploadRepository.notifyChange(tx, uploadId)
            }
        }
        meterRegistry.timer("upload.processing", "result", "success")
            .record(Duration.ofNanos(System.nanoTime() - startTime))
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
        userToken: String,
    ) {
        val (filId, navEksternRefId) = withContext(Dispatchers.IO) {
            dsl.transactionResult { tx ->
                val upload = uploadRepository.getUpload(tx, uploadId)
                uploadRepository.deleteUpload(tx, uploadId)
                upload.filId to upload.navEksternRefId
            }
        }

        if (filId != null && navEksternRefId != null) {
            runCatching {
                mellomlagringClient.deleteFile(navEksternRefId, filId, userToken)
            }.onFailure {
                logger.warn("Failed to delete file $filId from mellomlagring after upload deletion; it may be orphaned", it)
            }
        }
    }

    class UploadForbiddenException(
        message: String,
    ) : RuntimeException(message)
}


