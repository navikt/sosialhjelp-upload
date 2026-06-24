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
    private val uploadRepository: UploadRepository,
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
        val upload = withContext(ioDispatcher) {
            dsl.transactionResult { tx -> uploadRepository.getUploadForProcessing(tx, uploadId) }
        }
        val fileExtension = File(upload.filename).extension.lowercase().ifEmpty { "none" }

        upload.fiksDigisosId?.let { MDC.put("fiksDigisosId", it) }
        MDC.put("navEksternRefId", upload.navEksternRefId)
        try {
            withContext(MDCContext()) {
                // Step 1: Assemble chunks
                val (rawData, composedKey) = chunkAssemblyService.assembleChunks(uploadId, upload.gcsKey)

                // Step 2: Validate
                val errors = validator.validate(upload.filename, rawData, rawData.size.toLong())
                if (errors.isNotEmpty()) {
                    logger.info("Upload $uploadId (*$fileExtension) failed validation: ${errors.map { "${it.code}: ${it.message}" }}")
                    withContext(ioDispatcher) {
                        dsl.transaction { tx -> uploadRepository.addErrors(tx, uploadId, errors) }
                    }
                    chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
                    meterRegistry.timer("upload.processing", "result", "validation_failure", "extension", fileExtension)
                        .record(Duration.ofNanos(System.nanoTime() - startTime))
                    return@withContext
                }

                // Step 3: Convert
                val (finalFilename, finalData) = try {
                    fileConversionService.convertIfNeeded(upload.filename, rawData)
                } catch (e: Exception) {
                    logger.error("Upload $uploadId failed during PDF conversion", e)
                    markUploadFailed(uploadId, composedKey)
                    meterRegistry.timer("upload.processing", "result", "conversion_failure", "extension", fileExtension)
                        .record(Duration.ofNanos(System.nanoTime() - startTime))
                    return@withContext
                }
                val finalExtension = File(finalFilename).extension.lowercase().ifEmpty { "none" }
                meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()

                // Step 4: Store in mellomlagring
                val storageResult = try {
                    mellomlagringStorageService.store(upload.navEksternRefId, finalFilename, uploadId, finalData)
                } catch (e: Exception) {
                    logger.error("Upload $uploadId failed during mellomlagring upload", e)
                    markUploadFailed(uploadId, composedKey)
                    meterRegistry.timer("upload.processing", "result", "mellomlagring_failure", "extension", fileExtension)
                        .record(Duration.ofNanos(System.nanoTime() - startTime))
                    return@withContext
                }
                logger.info("Upload $uploadId stored in mellomlagring as ${storageResult.filId}")

                // Step 5: Finalize — update DB and clean up GCS
                withContext(ioDispatcher) {
                    dsl.transaction { tx ->
                        uploadRepository.setFilId(
                            tx, uploadId,
                            storageResult.filId,
                            storageResult.mellomlagringFilnavn,
                            storageResult.storedSize,
                            getSha512(finalData),
                        )
                        uploadRepository.notifyChange(tx, uploadId)
                    }
                }
                chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
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
        }
        chunkAssemblyService.deleteGcsObjects(uploadId, composedKey)
    }
}
