package no.nav.sosialhjelp.upload.validation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import java.io.IOException
import java.text.Normalizer

// 10 MB
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
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    suspend fun validate(
        filename: String,
        data: ByteArray,
        fileSize: Long,
    ): List<Validation> =
        coroutineScope {
            val virusScanValidation = async(Dispatchers.IO) { runVirusScan(data) }
            val (mimeType, fileTypeValidation) = validateFileType(data)
            listOfNotNull(
                validateFileSize(fileSize),
                validateFilename(Filename(filename)),
                fileTypeValidation,
                if (mimeType == "application/pdf") validatePdf(data) else null,
                virusScanValidation.await(),
            )
        }

    private suspend fun validatePdf(data: ByteArray): Validation? =
        withContext(Dispatchers.IO) {
            try {
                val randomAccessRead = RandomAccessReadBuffer(data.inputStream())
                Loader
                    .loadPDF(randomAccessRead)
                    .use { document ->
                        if (document.isEncrypted) {
                            EncryptedPdfValidation()
                        } else {
                            null
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

    private suspend fun validateFileType(data: ByteArray): Pair<String, Validation?> =
        withContext(Dispatchers.IO) {
            val mimeType = Tika().detect(data.inputStream())
            if (mimeType !in SUPPORTED_MIME_TYPES) {
                return@withContext mimeType to FileTypeValidation(actual = mimeType)
            }
            return@withContext mimeType to null
        }

    private suspend fun runVirusScan(data: ByteArray): Validation? =
        when (virusScanner.scan(data)) {
            Result.FOUND -> VirusValidation()
            Result.ERROR -> {
                logger.warn("Virus scanner returned ERROR, treating file as clean")
                null
            }
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
