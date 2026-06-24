package no.nav.sosialhjelp.upload.upload

import io.ktor.http.ContentType
import io.ktor.http.defaultForFile
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.tus.storage.ChunkStorage
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.File
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

/**
 * Handles the post-upload processing pipeline once all TUS chunks have been received:
 * assemble → validate → convert → encrypt → upload to mellomlagring → update DB.
 */
class UploadProcessingService(
    private val dsl: DSLContext,
    private val uploadRepository: UploadRepository,
    private val validator: UploadValidator,
    private val gotenbergService: GotenbergService,
    private val mellomlagringClient: MellomlagringClient,
    private val encryptionService: EncryptionService,
    private val chunkStorage: ChunkStorage,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun process(uploadId: UUID) {
        val startTime = System.nanoTime()
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploadForProcessing(tx, uploadId)
                }
            }
        val fileExtension = File(upload.filename).extension.lowercase().ifEmpty { "none" }

        upload.fiksDigisosId?.let { MDC.put("fiksDigisosId", it) }
        MDC.put("navEksternRefId", upload.navEksternRefId)
        try {
            withContext(MDCContext()) {
                val (chunkData, composedKey) = withContext(ioDispatcher) {
                    val chunkPrefix = "uploads/$uploadId-chunk-"
                    val chunkKeys = chunkStorage.listKeys(chunkPrefix).sortedBy { key ->
                        key.removePrefix(chunkPrefix).toLongOrNull() ?: 0L
                    }
                    if (chunkKeys.isEmpty()) {
                        error("No chunk objects found for upload $uploadId at prefix $chunkPrefix")
                    }
                    // Use a unique key per attempt so we never try to overwrite an existing GCS object,
                    // which would be rejected with 403 if a previous composed object is still present.
                    val composedKey = "${upload.gcsKey}-${UUID.randomUUID()}"
                    chunkStorage.composeChunks(chunkKeys, composedKey)
                    chunkStorage.readObject(composedKey) to composedKey
                }

                val errors = validator.validate(upload.filename, chunkData, chunkData.size.toLong())
                if (errors.isNotEmpty()) {
                    logger.info("Upload $uploadId (*$fileExtension) failed validation: ${errors.map { "${it.code}: ${it.message}" }}")
                    withContext(ioDispatcher) {
                        dsl.transaction { tx ->
                            uploadRepository.addErrors(tx, uploadId, errors)
                        }
                        deleteGcsObjects(uploadId, composedKey)
                    }
                    meterRegistry.timer("upload.processing", "result", "validation_failure", "extension", fileExtension)
                        .record(Duration.ofNanos(System.nanoTime() - startTime))
                    return@withContext
                }

                val (finalFilename, finalData) = try {
                    convertIfNeeded(upload.filename, chunkData)
                } catch (e: Exception) {
                    logger.error("Upload $uploadId failed during PDF conversion", e)
                    markUploadFailed(uploadId, composedKey)
                    meterRegistry.timer("upload.processing", "result", "conversion_failure", "extension", fileExtension)
                        .record(Duration.ofNanos(System.nanoTime() - startTime))
                    return@withContext
                }
                val finalExtension = File(finalFilename).extension.lowercase().ifEmpty { "none" }
                meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()
                val mellomlagringFilnavn = makeUniqueMellomlagringFilename(finalFilename, uploadId)
                val encrypted = encryptionService.encryptBytes(finalData)

                val contentType =
                    ContentType
                        .defaultForFile(File(finalFilename))
                        .toString()

                logger.info("Uploading file (${encrypted.size} bytes) to mellomlagring for ${upload.navEksternRefId}")
                val filId =
                    try {
                        mellomlagringClient.uploadFile(
                            navEksternRefId = upload.navEksternRefId,
                            filename = mellomlagringFilnavn,
                            contentType = contentType,
                            data = encrypted,
                        )
                    } catch (e: Exception) {
                        logger.error("Upload $uploadId failed during mellomlagring upload", e)
                        markUploadFailed(uploadId, composedKey)
                        meterRegistry.timer("upload.processing", "result", "mellomlagring_failure", "extension", fileExtension)
                            .record(Duration.ofNanos(System.nanoTime() - startTime))
                        return@withContext
                    }
                logger.info("Upload $uploadId stored in mellomlagring as $filId")

                withContext(ioDispatcher) {
                    dsl.transaction { tx ->
                        uploadRepository.setFilId(tx, uploadId, filId, mellomlagringFilnavn, finalData.size.toLong(), getSha512(finalData))
                        uploadRepository.notifyChange(tx, uploadId)
                    }
                    deleteGcsObjects(uploadId, composedKey)
                }
                meterRegistry.timer("upload.processing", "result", "success", "extension", fileExtension)
                    .record(Duration.ofNanos(System.nanoTime() - startTime))
            }
        } finally {
            MDC.remove("fiksDigisosId")
            MDC.remove("navEksternRefId")
        }
    }

    suspend fun markUploadFailed(uploadId: UUID, composedKey: String? = null) {
        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadRepository.markFailed(tx, uploadId)
                uploadRepository.notifyChange(tx, uploadId)
            }
            deleteGcsObjects(uploadId, composedKey)
        }
    }

    suspend fun deleteGcsObjects(uploadId: UUID, composedKey: String? = null) {
        val chunkPrefix = "uploads/$uploadId-chunk-"
        val keysToDelete = buildList {
            add(composedKey ?: "uploads/$uploadId")
        }
        runCatching {
            val chunkKeys = chunkStorage.listKeys(chunkPrefix)
            (chunkKeys + keysToDelete).distinct().forEach { key ->
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
}

fun getSha512(data: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-512")
    md.update(data)
    val digest = md.digest()
    return buildString(digest.size * 2) {
        digest.forEach { append("%02x".format(it)) }
    }
}

/**
 * Generates a unique mellomlagring filename by appending the first segment of [uploadId]
 * (8 hex characters) before the extension. The base name is truncated to 50 characters
 * to keep total filenames manageable.
 *
 * Examples:
 *   "document.pdf"  + UUID b1f7f6d1-... → "document-b1f7f6d1.pdf"
 *   "a".repeat(60) + ".pdf" → "a".repeat(50) + "-b1f7f6d1.pdf"
 */
internal fun makeUniqueMellomlagringFilename(filename: String, uploadId: UUID): String {
    val file = File(filename)
    val extension = file.extension
    val baseName = file.nameWithoutExtension.take(50)
    val uuidSuffix = uploadId.toString().substringBefore('-')
    return if (extension.isNotEmpty()) "$baseName-$uuidSuffix.$extension" else "$baseName-$uuidSuffix"
}
