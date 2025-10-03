@file:Suppress("INFERRED_INVISIBLE_RETURN_TYPE_WARNING")

package no.nav.sosialhjelp.upload.validation

import io.ktor.util.logging.Logger
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.nav.sosialhjelp.upload.fs.Storage
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.tika.Tika
import java.io.IOException
import java.text.Normalizer

// 10 MB
// TODO: Er dette en begrensning hos oss, Fiks eller fagsystemene?
const val MAX_FILE_SIZE = 10 * 1024 * 1024

val SUPPORTED_MIME_TYPES =
    setOf(
        "text/plain",
        // .docx er i realiteten .zip-filer
        "application/zip",
        "application/pdf",
        "image/png",
        "image/jpeg",
        "image/tiff",
        "image/bmp",
        "image/gif",
        "application/msword",
        "application/x-tika-ooxml",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.oasis.opendocument.text",
    )

class UploadValidator(
    val virusScanner: VirusScanner,
    val logger: Logger,
    val storage: Storage,
) {
    suspend fun validate(request: HookRequest): List<Validation> {
        val file = storage.retrieve(request.event.upload.id) ?: error("File not found: ${request.event.upload.id}")

        val validations =
            coroutineScope {
                val virusScanValidation = async(Dispatchers.IO) { runVirusScan(file) }

                listOf(
                    validateFileSize(request.event.upload.size),
                    validateFilename(Filename(request.event.upload.metadata.filename)),
                    validateFileType(file),
                    virusScanValidation.await(),
                )
            }
        return validations.filterNotNull()
    }

    private fun validateFileType(file: ByteReadChannel): Validation? {
        val mimeType = Tika().detect(file.toInputStream())
        if (mimeType !in SUPPORTED_MIME_TYPES) {
            println("Unsupported mime type: $mimeType")
            return FileTypeValidation()
        }
        println("Detected mime type: $mimeType")
        if (mimeType == "application/pdf") {
            file.checkIfPdfIsValid()
        }
        return null
    }

    private fun ByteReadChannel.checkIfPdfIsValid(): Validation? {
        return try {
            val randomAccessRead = RandomAccessReadBuffer(this.toInputStream())
            Loader
                .loadPDF(randomAccessRead)
                .use { document ->
                    if (document.isEncrypted) {
                        EncryptedPdfValidation()
                    }
                    return null
                }
        } catch (e: InvalidPasswordException) {
            logger.warn(ValidationCode.ENCRYPTED_PDF.name + " " + e.message)
            EncryptedPdfValidation()
        } catch (e: IOException) {
            logger.warn(ValidationCode.INVALID_PDF.name, e)
            InvalidPdfValidation()
        } catch (e: Exception) {
            logger.warn(ValidationCode.INVALID_PDF.name + " " + e.message, e)
            InvalidPdfValidation()
        }
    }

    private suspend fun runVirusScan(file: ByteReadChannel): Validation? =
        when (virusScanner.scan(file)) {
            Result.FOUND -> VirusValidation()
            Result.ERROR -> TODO()
            Result.OK -> null
        }

    private fun validateFileSize(filesize: Long): Validation? {
        if (filesize > MAX_FILE_SIZE) {
            return FileSizeValidation()
        }
        return null
    }

    private fun validateFilename(filename: Filename): Validation? {
        if (filename.containsIllegalCharacters()) {
            return FilenameValidation()
        }
        return null
    }
}

@JvmInline
private value class Filename(
    val value: String,
) {
    fun sanitize() = Normalizer.normalize(value, Normalizer.Form.NFC).trim()

    fun containsIllegalCharacters(): Boolean = this.sanitize().contains("[^a-zæøåA-ZÆØÅ0-9 (),._–-]".toRegex())
}
