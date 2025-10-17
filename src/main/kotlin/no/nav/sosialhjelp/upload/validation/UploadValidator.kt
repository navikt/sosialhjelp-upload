package no.nav.sosialhjelp.upload.validation

import io.ktor.util.cio.readChannel
import io.ktor.util.cio.use
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.fs.Storage
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import java.io.IOException
import java.text.Normalizer
import kotlin.io.path.createTempFile

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
    val storage: Storage,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun retrieveFile(uploadId: String): ByteReadChannel = storage.retrieve(uploadId) ?: error("File not found: $uploadId")

    suspend fun validate(request: HookRequest): List<Validation> {
        // Fetch the file once and write to a temp file in /tmp
        val uploadId = request.event.upload.id
        val channel = retrieveFile(uploadId)
        val tempFile = withContext(Dispatchers.IO) {
            createTempFile(prefix = uploadId, suffix = ".tmp").toFile().also { tempFile ->
                tempFile.writeChannel().use {
                    channel.copyTo(this)
                    flush()
                }
            }
        }
        try {
            val validations =
                coroutineScope {
                    val virusScanValidation =
                        async(Dispatchers.IO) {
                            runVirusScan(tempFile.readChannel())
                        }
                    val (mimeType, fileTypeValidation) = validateFileType(tempFile.readChannel())
                    listOfNotNull(
                        validateFileSize(request.event.upload.size),
                        validateFilename(Filename(request.event.upload.metadata.filename)),
                        fileTypeValidation,
                        if (mimeType == "application/pdf") validatePdf(tempFile.readChannel()) else null,
                        virusScanValidation.await(),
                    )
                }
            return validations
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun validatePdf(file: ByteReadChannel): Validation? = withContext(Dispatchers.IO) {
        try {
            file.toInputStream().use { inputStream ->
                val randomAccessRead = RandomAccessReadBuffer(inputStream)
                Loader
                    .loadPDF(randomAccessRead)
                    .use { document ->
                        if (document.isEncrypted) {
                            EncryptedPdfValidation()
                        } else null
                    }
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

    private suspend fun validateFileType(file: ByteReadChannel): Pair<String, Validation?> = withContext(Dispatchers.IO) {
        val mimeType = file.toInputStream().use { Tika().detect(it) }
        if (mimeType !in SUPPORTED_MIME_TYPES) {
            println("Unsupported mime type: $mimeType")
            return@withContext mimeType to FileTypeValidation()
        }
        println("Detected mime type: $mimeType")
        return@withContext mimeType to null
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
