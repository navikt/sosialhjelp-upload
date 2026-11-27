package no.nav.sosialhjelp.upload.upload

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.upload.FileConversionService.ConversionResult
import no.nav.sosialhjelp.upload.validation.FileTypeValidation
import no.nav.sosialhjelp.upload.validation.UploadValidator
import no.nav.sosialhjelp.upload.validation.ValidationCode
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FilePipelineTest {
    private val validator = mockk<UploadValidator>()
    private val converter = mockk<FileConversionService>()
    private val storage = mockk<MellomlagringStorageService>()
    private val registry = SimpleMeterRegistry()
    private val pipeline = FilePipeline(validator, converter, storage, registry)

    private val uploadId = UUID.randomUUID()
    private val filId = UUID.randomUUID()
    private val data = "data".toByteArray()

    private fun valid() {
        coEvery { validator.validate(any(), any<ByteArray>(), any()) } returns
            UploadValidator.FileValidationResult("application/pdf", emptyList())
    }

    private fun stored() {
        coEvery { storage.store(any(), any(), any(), any(), any()) } returns
            MellomlagringStorageService.StorageResult(filId, "a-12345678.pdf", 42)
    }

    private fun timerCount(result: String) =
        registry
            .find("upload.processing")
            .tag("result", result)
            .timers()
            .sumOf { it.count() }
            .toInt()

    @Test
    fun `stored without conversion`() =
        runBlocking {
            valid()
            coEvery { converter.convertIfNeeded(any(), any(), any()) } returns
                ConversionResult.Success("a.pdf", data, "application/pdf", false)
            stored()

            val prepared = pipeline.prepare(uploadId, "a.pdf", data)
            assertIs<PrepareResult.Ready>(prepared)
            val result = pipeline.store(prepared, uploadId, "ref")

            assertIs<PipelineResult.Stored>(result)
            assertEquals(filId, result.filId)
            assertEquals(42, result.storedSize)
            assertEquals(false, result.converted)
            assertEquals("application/pdf", result.contentType)
            assertEquals(getSha512(data), result.sha512)
            assertEquals(1, timerCount("success"))
        }

    @Test
    fun `stored with conversion`() =
        runBlocking {
            valid()
            coEvery { converter.convertIfNeeded(any(), any(), any()) } returns
                ConversionResult.Success("a.pdf", data, "application/pdf", true)
            stored()

            val prepared = pipeline.prepare(uploadId, "a.docx", data)
            assertIs<PrepareResult.Ready>(prepared)
            val result = pipeline.store(prepared, uploadId, "ref")

            assertIs<PipelineResult.Stored>(result)
            assertEquals(true, result.converted)
            assertEquals(1.0, registry.counter("upload.converted_file_extension", "extension", "pdf").count())
        }

    @Test
    fun `rejected by validation`() =
        runBlocking {
            coEvery { validator.validate(any(), any<ByteArray>(), any()) } returns
                UploadValidator.FileValidationResult("application/pdf", listOf(FileTypeValidation("x")))

            val result = pipeline.prepare(uploadId, "a.pdf", data)

            assertIs<PrepareResult.Rejected>(result)
            assertEquals(ValidationCode.FILETYPE_NOT_SUPPORTED, result.errors.single().code)
            assertEquals(1, timerCount("validation_failure"))
        }

    @Test
    fun `rejected as unsupported type`() =
        runBlocking {
            valid()
            coEvery { converter.convertIfNeeded(any(), any(), any()) } returns
                ConversionResult.UnsupportedFiletype("xyz")

            val result = pipeline.prepare(uploadId, "a.xyz", data)

            assertIs<PrepareResult.Rejected>(result)
            assertEquals(ValidationCode.FILETYPE_NOT_SUPPORTED, result.errors.single().code)
            assertEquals(1.0, registry.counter("upload.gotenberg_unsupported", "extension", "xyz").count())
            assertEquals(1, timerCount("validation_failure"))
        }

    @Test
    fun `failed at conversion`() =
        runBlocking {
            valid()
            val boom = RuntimeException("boom")
            coEvery { converter.convertIfNeeded(any(), any(), any()) } throws boom

            val result = pipeline.prepare(uploadId, "a.docx", data)

            assertIs<PrepareResult.Failed>(result)
            assertEquals(PipelineResult.Failed.Stage.CONVERSION, result.stage)
            assertEquals(boom, result.cause)
            assertEquals(1, timerCount("conversion_failure"))
        }

    @Test
    fun `failed at storage`() =
        runBlocking {
            valid()
            coEvery { converter.convertIfNeeded(any(), any(), any()) } returns
                ConversionResult.Success("a.pdf", data, "application/pdf", false)
            coEvery { storage.store(any(), any(), any(), any(), any()) } throws RuntimeException("down")

            val prepared = pipeline.prepare(uploadId, "a.pdf", data)
            assertIs<PrepareResult.Ready>(prepared)
            val result = pipeline.store(prepared, uploadId, "ref")

            assertIs<PipelineResult.Failed>(result)
            assertEquals(PipelineResult.Failed.Stage.STORAGE, result.stage)
            assertEquals(1, timerCount("mellomlagring_failure"))
        }
}
