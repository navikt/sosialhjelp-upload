@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * Orchestrates the post-upload processing pipeline:
 * assemble → validate → convert → store → finalize.
 *
 * Each step is delegated to a dedicated service. This class is responsible
 * for reading upload metadata, coordinating the steps, updating the DB,
 * recording metrics, and handling failures.
 */
class UploadProcessingService(
    private val dsl: DSLContext,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val validator: UploadValidator,
    private val fileConversionService: FileConversionService,
    private val mellomlagringStorageService: MellomlagringStorageService,
    private val meterRegistry: MeterRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun process(uploadId: UUID) {
        val startTime = System.nanoTime()
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadProcessingQueries.getUploadForProcessing(tx, uploadId) }
            }
        val fileExtension = File(upload.filename).extension.lowercase().ifEmpty { "none" }

        upload.fiksDigisosId?.let { MDC.put("fiksDigisosId", it) }
        MDC.put("navEksternRefId", upload.navEksternRefId)
        try {
            withContext(MDCContext()) {
                val (rawData, composedKey) = chunkAssemblyService.assembleChunks(uploadId, upload.gcsKey)

                if (!validateUpload(uploadId, upload.filename, fileExtension, rawData, composedKey, startTime)) {
                    return@withContext
                }

                val (finalFilename, finalData) =
                    convertUpload(uploadId, upload.filename, fileExtension, rawData, composedKey, startTime)
                        ?: return@withContext

                val storageResult =
                    storeUpload(
                        uploadId, fileExtension, upload.navEksternRefId,
                        finalFilename, finalData, composedKey, startTime,
                    ) ?: return@withContext

                finalizeUpload(uploadId, fileExtension, finalData, storageResult, composedKey, startTime)
            }
        } finally {
            MDC.remove("fiksDigisosId")
            MDC.remove("navEksternRefId")
        }
    }

    private suspend fun validateUpload(
        uploadId: UUID,
        filename: String,
        fileExtension: String,
        rawData: ByteArray,
        composedKey: String,
        startTime: Long,
    ): Boolean {
        val errors = validator.validate(filename, rawData, rawData.size.toLong())
        if (errors.isEmpty()) return true

        logger.info(
            "Upload $uploadId (*$fileExtension) failed validation: " +
                "${errors.map { "${it.code}: ${it.message}" }}",
        )
        withContext(ioDispatcher) {
            dsl.transaction { tx -> uploadProcessingQueries.addErrors(tx, uploadId, errors) }
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
        recordTimer(fileExtension, "validation_failure", startTime)
        return false
    }

    private suspend fun convertUpload(
        uploadId: UUID,
        filename: String,
        fileExtension: String,
        rawData: ByteArray,
        composedKey: String,
        startTime: Long,
    ): Pair<String, ByteArray>? =
        try {
            val result = fileConversionService.convertIfNeeded(filename, rawData)
            val finalExtension = File(result.first).extension.lowercase().ifEmpty { "none" }
            meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()
            result
        } catch (e: Exception) {
            logger.error("Upload $uploadId failed during PDF conversion", e)
            markUploadFailed(uploadId, composedKey)
            recordTimer(fileExtension, "conversion_failure", startTime)
            null
        }

    private suspend fun storeUpload(
        uploadId: UUID,
        fileExtension: String,
        navEksternRefId: String,
        finalFilename: String,
        finalData: ByteArray,
        composedKey: String,
        startTime: Long,
    ): MellomlagringStorageService.StorageResult? =
        try {
            val result = mellomlagringStorageService.store(navEksternRefId, finalFilename, uploadId, finalData)
            logger.info("Upload $uploadId stored in mellomlagring as ${result.filId}")
            result
        } catch (e: Exception) {
            logger.error("Upload $uploadId failed during mellomlagring upload", e)
            markUploadFailed(uploadId, composedKey)
            recordTimer(fileExtension, "mellomlagring_failure", startTime)
            null
        }

    private suspend fun finalizeUpload(
        uploadId: UUID,
        fileExtension: String,
        finalData: ByteArray,
        storageResult: MellomlagringStorageService.StorageResult,
        composedKey: String,
        startTime: Long,
    ) {
        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadProcessingQueries.setFilId(
                    tx,
                    uploadId,
                    storageResult.filId,
                    storageResult.mellomlagringFilnavn,
                    storageResult.storedSize,
                    getSha512(finalData),
                )
                UploadNotifications.notifyChange(tx, uploadId)
            }
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
        recordTimer(fileExtension, "success", startTime)
    }

    private fun recordTimer(
        fileExtension: String,
        result: String,
        startTime: Long,
    ) {
        meterRegistry
            .timer("upload.processing", "result", result, "extension", fileExtension)
            .record(Duration.ofNanos(System.nanoTime() - startTime))
    }

    suspend fun markUploadFailed(
        uploadId: UUID,
        composedKey: String? = null,
    ) {
        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadProcessingQueries.markFailed(tx, uploadId)
                UploadNotifications.notifyChange(tx, uploadId)
            }
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
    }
}
