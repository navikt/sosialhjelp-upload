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

sealed interface StoreResult

sealed interface PrepareResult {
    class Ready(
        val filename: String,
        val data: ByteArray,
        val contentType: String,
        val converted: Boolean,
        val sha512: String,
        internal val fileExtension: String,
        internal val startTime: Long,
    ) : PrepareResult

    data class Rejected(
        val errors: List<Validation>,
    ) : PrepareResult

    data class Failed(
        val stage: PipelineResult.Failed.Stage,
        val cause: Throwable,
    ) : PrepareResult
}

sealed interface PipelineResult {
    data class Stored(
        val filId: UUID,
        val mellomlagringFilnavn: String,
        val storedSize: Long,
        val sha512: String,
        val converted: Boolean,
        val contentType: String,
    ) : PipelineResult,
        StoreResult

    data class Rejected(
        val errors: List<Validation>,
    ) : PipelineResult

    data class Failed(
        val stage: Stage,
        val cause: Throwable,
    ) : PipelineResult,
        StoreResult {
        enum class Stage { CONVERSION, STORAGE }
    }
}

class FilePipeline(
    private val validator: UploadValidator,
    private val fileConversionService: FileConversionService,
    private val mellomlagringStorageService: MellomlagringStorageService,
    private val meterRegistry: MeterRegistry,
    private val cpuDispatcher: CpuDispatcher = CpuDispatcher(),
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * Validate, convert and hash
     */
    suspend fun prepare(
        uploadId: UUID,
        filename: String,
        data: ByteArray,
    ): PrepareResult {
        val startTime = System.nanoTime()
        val fileExtension = File(filename).extension.lowercase().ifEmpty { "none" }

        val validation = validator.validate(filename, data, data.size.toLong())
        if (validation.errors.isNotEmpty()) {
            logger.info(
                "Upload $uploadId (*$fileExtension) failed validation: " +
                    "${validation.errors.map { "${it.code}: ${it.message}" }}",
            )
            recordTimer(fileExtension, "validation_failure", startTime)
            return PrepareResult.Rejected(validation.errors)
        }

        val converted =
            try {
                fileConversionService.convertIfNeeded(filename, validation.mimeType, data)
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during PDF conversion", e)
                recordTimer(fileExtension, "conversion_failure", startTime)
                return PrepareResult.Failed(PipelineResult.Failed.Stage.CONVERSION, e)
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
                    return PrepareResult.Rejected(listOf(FileTypeValidation(converted.extension)))
                }
                is FileConversionService.ConversionResult.Success -> {
                    val finalExtension = File(converted.filename).extension.lowercase().ifEmpty { "none" }
                    meterRegistry.counter("upload.converted_file_extension", "extension", finalExtension).increment()
                    converted
                }
            }

        val sha512 = withContext(cpuDispatcher) { getSha512(file.data) }
        return PrepareResult.Ready(
            filename = file.filename,
            data = file.data,
            contentType = file.contentType,
            converted = file.converted,
            sha512 = sha512,
            fileExtension = fileExtension,
            startTime = startTime,
        )
    }

    /**
     * Encrypts and stores in mellomlager
     */
    suspend fun store(
        prepared: PrepareResult.Ready,
        uploadId: UUID,
        navEksternRefId: String,
    ): StoreResult {
        val stored =
            try {
                mellomlagringStorageService
                    .store(navEksternRefId, prepared.filename, prepared.contentType, uploadId, prepared.data)
                    .also { logger.info("Upload $uploadId stored in mellomlagring as ${it.filId}") }
            } catch (e: Exception) {
                logger.error("Upload $uploadId failed during mellomlagring upload", e)
                recordTimer(prepared.fileExtension, "mellomlagring_failure", prepared.startTime)
                return PipelineResult.Failed(PipelineResult.Failed.Stage.STORAGE, e)
            }

        recordTimer(prepared.fileExtension, "success", prepared.startTime)
        return PipelineResult.Stored(
            filId = stored.filId,
            mellomlagringFilnavn = stored.mellomlagringFilnavn,
            storedSize = stored.storedSize,
            sha512 = prepared.sha512,
            converted = prepared.converted,
            contentType = prepared.contentType,
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
