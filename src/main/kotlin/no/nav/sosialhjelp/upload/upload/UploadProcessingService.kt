@file:Suppress("TooGenericExceptionCaught", "LongParameterList")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.withMdc
import no.nav.sosialhjelp.upload.upload.UploadProcessingQueries.Claim
import no.nav.sosialhjelp.upload.validation.FileTypeValidation
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.Validation
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration

/**
 * Runs one processing attempt: assemble → validate → convert → store → finalize.
 *
 * Every DB write is fenced on the claim's attempt id. If a write is fenced out, the upload was
 * deleted, failed by recovery or taken over by a newer attempt. This attempt then stops, removes
 * the mellomlagring file it stored and leaves the GCS chunks alone (a newer attempt may need them).
 */
class UploadProcessingService(
    private val dsl: DSLContext,
    private val uploadProcessingQueries: UploadProcessingQueries,
    private val chunkAssemblyService: ChunkAssemblyService,
    private val validator: UploadValidator,
    private val fileConversionService: FileConversionService,
    private val mellomlagringStorageService: MellomlagringStorageService,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    private class Attempt(
        val claim: Claim,
        val upload: UploadForProcessing,
        val startTime: Long = System.nanoTime(),
    ) {
        val uploadId get() = claim.uploadId
        val extension = File(upload.filename).extension.lowercase().ifEmpty { "none" }
        var composedKey: String? = null
    }

    suspend fun process(claim: Claim) {
        val upload =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx -> uploadProcessingQueries.getUploadForProcessing(tx, claim.uploadId) }
            }
        val attempt = Attempt(claim, upload)
        withMdc("fiksDigisosId" to upload.fiksDigisosId, "navEksternRefId" to upload.navEksternRefId) {
            try {
                run(attempt)
            } catch (e: CancellationException) {
                // Pod shutdown: leave the upload PROCESSING. The lease expires and another pod retries.
                throw e
            } catch (e: Exception) {
                logger.error("Upload ${attempt.uploadId} failed during processing (attempt ${claim.attempt})", e)
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

        val stored =
            mellomlagringStorageService.store(
                attempt.upload.navEksternRefId,
                converted.filename,
                converted.contentType,
                attempt.uploadId,
                converted.data,
                // A retry may follow an attempt that stored the file and then died.
                replaceExisting = attempt.claim.attempt > 1,
            )
        logger.info("Upload ${attempt.uploadId} stored in mellomlagring as ${stored.filId}")

        val sha512 = withContext(Dispatchers.Default) { getSha512(converted.data) }
        val completed =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadProcessingQueries
                        .setFilId(
                            tx,
                            attempt.uploadId,
                            attempt.claim.attemptId,
                            stored.filId,
                            stored.mellomlagringFilnavn,
                            stored.storedSize,
                            sha512,
                            converted.converted,
                            converted.contentType,
                        ).also { if (it) UploadNotifications.notifyChange(tx, attempt.uploadId) }
                }
            }
        if (!completed) {
            logger.warn("Upload ${attempt.uploadId}: attempt was fenced out, removing file ${stored.filId}")
            runCatching { mellomlagringStorageService.delete(attempt.upload.navEksternRefId, stored.filId) }
                .onFailure { logger.error("Failed to remove orphaned mellomlagring file ${stored.filId}", it) }
            cleanupOwnObject(attempt)
            return
        }
        chunkAssemblyService.deleteGcsObjects(attempt.uploadId, attempt.composedKey)
        recordTimer(attempt.extension, "success", attempt.startTime)
    }

    /** Marks the upload FAILED (with [errors]) if this attempt still owns it. */
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
                            .markFailed(tx, attempt.uploadId, attempt.claim.attemptId)
                            .also { if (it) UploadNotifications.notifyChange(tx, attempt.uploadId) }
                    } else {
                        uploadProcessingQueries.addErrors(tx, attempt.uploadId, errors, attempt.claim.attemptId)
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
