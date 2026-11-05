@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.validation.FileTypeValidation
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.Validation
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.UUID

/**
 * Runs one processing attempt: assemble → validate → convert → store → finalize.
 * Completion is conditional on the upload still being in PROCESSING.
 */
class UploadProcessingService(
    private val dsl: DSLContext,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val validator: UploadValidator,
    private val fileConversionService: FileConversionService,
    private val mellomlagringStorageService: MellomlagringStorageService,
    private val meterRegistry: MeterRegistry,
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private class Attempt(
        val uploadId: UUID,
        val upload: UploadForProcessing,
        val startTime: Long = System.nanoTime(),
    ) {
        val extension = File(upload.filename).extension.lowercase().ifEmpty { "none" }
        var composedKey: String? = null
    }

    suspend fun process(uploadId: UUID) {
        val upload =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx -> uploadProcessingQueries.getUploadForProcessing(tx, uploadId) }
            }
        val attempt = Attempt(uploadId, upload)
        withMdc("fiksDigisosId" to upload.fiksDigisosId, "navEksternRefId" to upload.navEksternRefId) {
            try {
                run(attempt)
            } catch (e: CancellationException) {
                // Shutdown leaves the upload PROCESSING for the recovery sweep.
                throw e
            } catch (e: Exception) {
                logger.error("Upload ${attempt.uploadId} failed during processing", e)
                fail(attempt, "processing_failure", emptyList())
            }
        }
    }

    @Suppress("LongMethod")
    private suspend fun run(attempt: Attempt) {
        val (rawData, composedKey) = chunkAssemblyService.assembleChunks(attempt.uploadId, attempt.upload.gcsKey)
        attempt.composedKey = composedKey

        val validation = validator.validate(attempt.upload.filename, rawData, rawData.size.toLong())
        if (validation.errors.isNotEmpty()) {
            logger.info(
                "Upload ${attempt.uploadId} (*${attempt.extension}) failed validation: " +
                    "${validation.errors.map { "${it.code}: ${it.message}" }}",
            )
            return fail(attempt, "validation_failure", validation.errors)
        }

        val converted =
            when (
                val result =
                    fileConversionService.convertIfNeeded(attempt.upload.filename, validation.mimeType, rawData)
            ) {
                is FileConversionService.ConversionResult.UnsupportedFiletype -> {
                    logger.info("Upload ${attempt.uploadId} (*${result.extension}) rejected by Gotenberg")
                    meterRegistry.counter("upload.gotenberg_unsupported", "extension", result.extension).increment()
                    return fail(attempt, "validation_failure", listOf(FileTypeValidation(result.extension)))
                }
                is FileConversionService.ConversionResult.Success -> result
            }
        val finalExtension = File(converted.filename).extension.lowercase().ifEmpty { "none" }
        meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()

        val sha512 = withContext(cpuDispatcher) { getSha512(converted.data) }
        val stored =
            mellomlagringStorageService.store(
                attempt.upload.navEksternRefId,
                converted.filename,
                converted.contentType,
                attempt.uploadId,
                converted.data,
            )
        logger.info("Upload ${attempt.uploadId} stored in mellomlagring as ${stored.filId}")
        // On an uncertain commit outcome the remote file must not be deleted: the DB may refer to it.
        val completed =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    val updated =
                        uploadProcessingQueries.setFilId(
                            tx,
                            attempt.uploadId,
                            stored.filId,
                            stored.mellomlagringFilnavn,
                            stored.storedSize,
                            sha512,
                            converted.converted,
                            converted.contentType,
                        )
                    if (updated) UploadNotifications.notifyChange(tx, attempt.uploadId)
                    updated
                }
            }
        if (!completed) {
            logger.warn("Upload ${attempt.uploadId} was deleted or failed before storage completed")
            runCatching { mellomlagringStorageService.delete(attempt.upload.navEksternRefId, stored.filId) }
                .onFailure { logger.warn("Failed to delete untracked file ${stored.filId}", it) }
            cleanupOwnObject(attempt)
            return
        }
        chunkAssemblyService.deleteGcsObjects(attempt.uploadId, attempt.composedKey)
        recordTimer(attempt.extension, "success", attempt.startTime)
    }

    /** Marks the upload FAILED (with [errors]) if it is still processing. */
    private suspend fun fail(
        attempt: Attempt,
        result: String,
        errors: List<Validation>,
    ) {
        val failed =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    if (errors.isEmpty()) {
                        uploadProcessingQueries
                            .markFailed(tx, attempt.uploadId, onlyProcessing = true)
                            .also { if (it) UploadNotifications.notifyChange(tx, attempt.uploadId) }
                    } else {
                        uploadProcessingQueries.addErrors(tx, attempt.uploadId, errors, onlyProcessing = true)
                    }
                }
            }
        if (failed) {
            chunkAssemblyService.deleteGcsObjects(attempt.uploadId, attempt.composedKey)
        } else {
            cleanupOwnObject(attempt)
        }
        recordTimer(attempt.extension, result, attempt.startTime)
    }

    private suspend fun cleanupOwnObject(attempt: Attempt) {
        attempt.composedKey?.let { chunkAssemblyService.deleteObject(it) }
    }

    suspend fun markTimedOut(uploadId: UUID) {
        val failed =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    val updated = uploadProcessingQueries.markFailed(tx, uploadId, onlyProcessing = true)
                    if (updated) UploadNotifications.notifyChange(tx, uploadId)
                    updated
                }
            }
        if (failed) {
            chunkAssemblyService.deleteGcsObjects(uploadId)
        }
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
}
