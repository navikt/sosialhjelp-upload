@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.validation.FileTypeValidation
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * Orchestrates the post-upload processing pipeline:
 * assemble → validate → convert → store → finalize.
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
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun process(uploadId: UUID) {
        val startTime = System.nanoTime()
        val upload =
            withContext(ioDispatcher) {
                dsl.transactionResult { tx -> uploadProcessingQueries.getUploadForProcessing(tx, uploadId) }
            }
        val fileExtension = File(upload.filename).extension.lowercase().ifEmpty { "none" }

        withMdc(
            "fiksDigisosId" to upload.fiksDigisosId,
            "navEksternRefId" to upload.navEksternRefId,
        ) {
            val (rawData, composedKey) = chunkAssemblyService.assembleChunks(uploadId, upload.gcsKey)

            val mimeType =
                validateUpload(uploadId, upload.filename, fileExtension, rawData, composedKey, startTime)
                    ?: return@withMdc

            val convertedFile =
                convertUpload(uploadId, upload.filename, mimeType, fileExtension, rawData, composedKey, startTime)
                    ?: return@withMdc

            val storageResult =
                storeUpload(
                    uploadId,
                    fileExtension,
                    upload.navEksternRefId,
                    convertedFile,
                    composedKey,
                    startTime,
                ) ?: return@withMdc

            finalizeUpload(uploadId, fileExtension, convertedFile, storageResult, composedKey, startTime)
        }
    }

    private suspend fun validateUpload(
        uploadId: UUID,
        filename: String,
        fileExtension: String,
        rawData: ByteArray,
        composedKey: String,
        startTime: Long,
    ): String? {
        val validationResult = validator.validate(filename, rawData, rawData.size.toLong())
        if (validationResult.errors.isEmpty()) return validationResult.mimeType

        logger.info(
            "Upload $uploadId (*$fileExtension) failed validation: " +
                "${validationResult.errors.map { "${it.code}: ${it.message}" }}",
        )
        withContext(ioDispatcher) {
            dsl.transaction { tx -> uploadProcessingQueries.addErrors(tx, uploadId, validationResult.errors) }
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
        recordTimer(fileExtension, "validation_failure", startTime)
        return null
    }

    private suspend fun convertUpload(
        uploadId: UUID,
        filename: String,
        mimeType: String,
        fileExtension: String,
        rawData: ByteArray,
        composedKey: String,
        startTime: Long,
    ): FileConversionService.ConversionResult.Success? =
        try {
            return when (val result = fileConversionService.convertIfNeeded(filename, mimeType, rawData)) {
                is FileConversionService.ConversionResult.UnsupportedFiletype -> {
                    logger.info(
                        "Upload $uploadId (*${result.extension}) rejected by Gotenberg: " +
                            "format not supported for conversion",
                    )
                    val validation = FileTypeValidation(result.extension)
                    withContext(ioDispatcher) {
                        dsl.transaction { tx -> uploadProcessingQueries.addErrors(tx, uploadId, listOf(validation)) }
                    }
                    chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                    meterRegistry.counter("upload.gotenberg_unsupported", "extension", result.extension).increment()
                    recordTimer(result.extension, "validation_failure", startTime)
                    null
                }
                is FileConversionService.ConversionResult.Success -> {
                    val finalExtension = File(result.filename).extension.lowercase().ifEmpty { "none" }
                    meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()
                    result
                }
            }
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
        convertedFile: FileConversionService.ConversionResult.Success,
        composedKey: String,
        startTime: Long,
    ): MellomlagringStorageService.StorageResult? =
        try {
            val result =
                mellomlagringStorageService.store(
                    navEksternRefId,
                    convertedFile.filename,
                    convertedFile.contentType,
                    uploadId,
                    convertedFile.data,
                )
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
        convertedFile: FileConversionService.ConversionResult.Success,
        storageResult: MellomlagringStorageService.StorageResult,
        composedKey: String,
        startTime: Long,
    ) {
        val sha512 = withContext(cpuDispatcher) { getSha512(convertedFile.data) }
        withContext(ioDispatcher) {
            dsl.transaction { tx ->
                uploadProcessingQueries.setFilId(
                    tx,
                    uploadId,
                    storageResult.filId,
                    storageResult.mellomlagringFilnavn,
                    storageResult.storedSize,
                    sha512,
                    convertedFile.converted,
                    convertedFile.contentType,
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
