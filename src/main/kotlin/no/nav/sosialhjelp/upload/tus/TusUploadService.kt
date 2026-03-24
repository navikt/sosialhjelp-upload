package no.nav.sosialhjelp.upload.tus

import io.ktor.http.ContentType
import io.ktor.http.defaultForFile
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.action.kryptering.EncryptionService
import no.nav.sosialhjelp.upload.common.FinishedUpload
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.DocumentRepository.DocumentOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class TusUploadService(
    private val uploadRepository: UploadRepository,
    private val documentRepository: DocumentRepository,
    private val dsl: DSLContext,
    private val validator: UploadValidator,
    private val gotenbergService: GotenbergService,
    private val mellomlagringClient: MellomlagringClient,
    private val encryptionService: EncryptionService,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)

    fun create(
        externalId: String,
        filename: String,
        size: Long,
        personident: String,
    ): UUID =
        try {
            dsl.transactionResult { tx ->
                val documentId = documentRepository.getOrCreateDocument(tx, externalId, personident)
                uploadRepository.create(tx, documentId, filename, size)
                    ?: error("Failed to create upload record")
            }
        } catch (_: DocumentOwnedByAnotherUserException) {
            throw UploadForbiddenException("Document is owned by another user")
        }

    fun getUploadInfo(
        uploadId: UUID,
        personident: String,
    ): Pair<Long, Long> =
        dsl.transactionResult { tx ->
            check(uploadRepository.isOwnedByUser(tx, uploadId, personident)) {
                "Upload $uploadId not found or not owned by $personident"
            }
            // returns offset to totalSize
            uploadRepository.getUploadInfo(tx, uploadId)
        }

    suspend fun appendChunk(
        uploadId: UUID,
        expectedOffset: Long,
        data: ByteArray,
        personident: String,
        userToken: String,
    ): Long {
        val (totalSize, newOffset) =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    check(uploadRepository.isOwnedByUser(tx, uploadId, personident)) {
                        "Upload $uploadId not owned by $personident"
                    }
                    uploadRepository.appendChunk(tx, uploadId, expectedOffset, data)
                }
            }

        if (newOffset == totalSize) {
            logger.info("Upload $uploadId complete, starting post-processing")
            processCompletedUpload(uploadId, userToken)
        }

        return newOffset
    }

    private suspend fun processCompletedUpload(
        uploadId: UUID,
        userToken: String,
    ) {
        val upload =
            withContext(Dispatchers.IO) {
                dsl.transactionResult { tx ->
                    uploadRepository.getUploadForProcessing(tx, uploadId)
                }
            }

        val errors = validator.validate(upload.filename, upload.chunkData, upload.chunkData.size.toLong())
        if (errors.isNotEmpty()) {
            logger.info("Upload $uploadId failed validation: ${errors.map { it.code }}")
            withContext(Dispatchers.IO) {
                dsl.transaction { tx ->
                    uploadRepository.addErrors(tx, uploadId, errors)
                    uploadRepository.clearChunkData(tx, uploadId)
                }
            }
            return
        }

        val (finalFilename, finalData) = convertIfNeeded(upload.filename, upload.chunkData)
        val encrypted = encryptionService.encryptBytes(finalData)

        val contentType =
            ContentType
                .defaultForFile(File(finalFilename))
                .toString()

        logger.info("Uploading $finalFilename (${encrypted.size} bytes) to mellomlagring for ${upload.externalId}")
        val filId =
            mellomlagringClient.uploadFile(
                navEksternRefId = upload.externalId,
                filename = finalFilename,
                contentType = contentType,
                data = encrypted,
                token = userToken,
            )
        logger.info("Upload $uploadId stored in mellomlagring as $filId")

        withContext(Dispatchers.IO) {
            dsl.transaction { tx ->
                uploadRepository.setFilId(tx, uploadId, filId, upload.externalId, finalFilename, encrypted.size.toLong())
                uploadRepository.clearChunkData(tx, uploadId)
                uploadRepository.notifyChange(tx, uploadId)
            }
        }
    }

    private suspend fun convertIfNeeded(
        filename: String,
        data: ByteArray,
    ): Pair<String, ByteArray> {
        val extension = File(filename).extension.lowercase()
        if (extension in listOf("pdf", "jpeg", "jpg", "png")) {
            return filename to data
        }
        val pdfName = File(filename).nameWithoutExtension + ".pdf"
        val converted =
            gotenbergService.convertToPdf(
                FinishedUpload(
                    file = ByteReadChannel(data),
                    originalFileExtension = extension,
                ),
            )
        val buffer = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val read = converted.readAvailable(buf)
            if (read == -1) break
            buffer.write(buf, 0, read)
        }
        return pdfName to buffer.toByteArray()
    }

    fun delete(
        uploadId: UUID,
        personident: String,
    ) {
        dsl.transaction { tx ->
            check(uploadRepository.isOwnedByUser(tx, uploadId, personident)) {
                "Upload $uploadId not owned by $personident"
            }
            uploadRepository.deleteUpload(tx, uploadId)
        }
    }

    class UploadForbiddenException(
        message: String,
    ) : RuntimeException(message)
}
