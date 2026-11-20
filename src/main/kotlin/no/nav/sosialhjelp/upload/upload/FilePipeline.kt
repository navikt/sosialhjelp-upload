@file:Suppress("TooGenericExceptionCaught", "LongParameterList", "ReturnCount")

package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.common.CpuDispatcher
import no.nav.sosialhjelp.upload.validation.FileTypeValidation
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.Validation
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.UUID

sealed interface PipelineResult {
    data class Stored(
        val filId: UUID,
        val mellomlagringFilnavn: String,
        val storedSize: Long,
        val sha512: String,
        val converted: Boolean,
        val contentType: String,
    ) : PipelineResult

    data class Rejected(
        val errors: List<Validation>,
    ) : PipelineResult

    data class Failed(
        val stage: Stage,
        val cause: Throwable,
    ) : PipelineResult {
        enum class Stage { CONVERSION, STORAGE }
    }
}

/**
 * validate → convert → store. No DB writes and no cleanup; only returns a result.
 */
class FilePipeline(
    private val validator: UploadValidator,
    private val fileConversionService: FileConversionService,
    private val mellomlagringStorageService: MellomlagringStorageService,
    private val meterRegistry: MeterRegistry,
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun run(
        uploadId: UUID,
        filename: String,
        data: ByteArray,
        navEksternRefId: String,
    ): PipelineResult {
        val startTime = System.nanoTime()
        val fileExtension = File(filename).extension.lowercase().ifEmpty { "none" }

        val validation = validator.validate(filename, data, data.size.toLong())
        if (validation.errors.isNotEmpty()) {
            logger.info(
                "Upload $uploadId (*$fileExtension) failed validation: " +
                    "${validation.errors.map { "${it.code}: ${it.message}" }}",
            )
            recordTimer(fileExtension, "validation_failure", startTime)
            return PipelineResult.Rejected(validation.errors)
        }

        val converted =
            try {
                fileConversionService.convertIfNeeded(filename, validation.mimeType, data)
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during PDF conversion", e)
                recordTimer(fileExtension, "conversion_failure", startTime)
                return PipelineResult.Failed(PipelineResult.Failed.Stage.CONVERSION, e)
            }

        val file =
            when (converted) {
                is FileConversionService.ConversionResult.UnsupportedFiletype -> {
                    logger.info(
                        "Upload $uploadId (*${converted.extension}) rejected by Gotenberg: " +
                            "format not supported for conversion",
                    )
                    meterRegistry.counter("upload.gotenberg_unsupported", "extension", converted.extension).increment()
                    recordTimer(converted.extension, "validation_failure", startTime)
                    return PipelineResult.Rejected(listOf(FileTypeValidation(converted.extension)))
                }
                is FileConversionService.ConversionResult.Success -> {
                    val finalExtension = File(converted.filename).extension.lowercase().ifEmpty { "none" }
                    meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()
                    converted
                }
            }

        val stored =
            try {
                mellomlagringStorageService
                    .store(navEksternRefId, file.filename, file.contentType, uploadId, file.data)
                    .also { logger.info("Upload $uploadId stored in mellomlagring as ${it.filId}") }
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during mellomlagring upload", e)
                recordTimer(fileExtension, "mellomlagring_failure", startTime)
                return PipelineResult.Failed(PipelineResult.Failed.Stage.STORAGE, e)
            }

        val sha512 = withContext(cpuDispatcher) { getSha512(file.data) }
        recordTimer(fileExtension, "success", startTime)
        return PipelineResult.Stored(
            filId = stored.filId,
            mellomlagringFilnavn = stored.mellomlagringFilnavn,
            storedSize = stored.storedSize,
            sha512 = sha512,
            converted = file.converted,
            contentType = file.contentType,
        )
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
