package no.nav.sosialhjelp.upload.tusd

import io.ktor.http.ContentType
import io.ktor.http.defaultForFileExtension
import io.ktor.util.logging.Logger
import no.nav.sosialhjelp.upload.common.FinishedUpload
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.DocumentRepository.DocumentOwnedByAnotherUserException
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.fs.Storage
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.upload.tusd.dto.HTTPResponse
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import no.nav.sosialhjelp.upload.tusd.dto.HookResponse
import no.nav.sosialhjelp.upload.tusd.input.CreateUploadRequest
import no.nav.sosialhjelp.upload.tusd.input.PostFinishRequest
import no.nav.sosialhjelp.upload.validation.UploadValidator
import org.jooq.DSLContext
import java.io.File
import java.util.*

class TusService(
    val logger: Logger,
    val uploadRepository: UploadRepository,
    val gotenbergService: GotenbergService,
    val documentRepository: DocumentRepository,
    val dsl: DSLContext,
    val validator: UploadValidator,
    val storage: Storage,
) {
    fun preCreate(
        request: HookRequest,
        personident: String,
    ): HookResponse {
        val uploadRequest = CreateUploadRequest.fromRequest(request)

        val uploadId =
            try {
                dsl.transactionResult { it ->
                    val documentId = documentRepository.getOrCreateDocument(it, uploadRequest.externalId, personident)
                    val uploadId = uploadRepository.create(it, documentId, uploadRequest.filename)
                    logger.info("Creating a new upload, ID: $uploadId for document $documentId")
                    uploadId
                }
            } catch (_: DocumentOwnedByAnotherUserException) {
                return HookResponse(
                    HTTPResponse(403, mapOf("Content-Type" to "application/json"), """"message": "Not yours""""),
                    rejectUpload = true,
                )
            }

        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    suspend fun postFinish(request: HookRequest) {
        val request = PostFinishRequest.fromRequest(request)
        // Ikke konverter det som fagsystemene godkjenner (pdf, jpg/jpeg, png)
        val extension = File(request.filename).extension
        val uploadId = request.uploadId
        val filename = if (extension !in listOf("pdf", "jpeg", "jpg", "png")) {
            val converted = convertUploadToPdf(uploadId, request.filename)
            val pdfName = File(request.filename).nameWithoutExtension + ".pdf"
            storage.store(pdfName, converted, "application/pdf")
            dsl.transactionResult { it ->
                uploadRepository.updateConvertedFilename(it, pdfName, uploadId)
            }
            pdfName
        } else {
            // If we don't convert, we just need to copy the file to the original file name
            val original = storage.retrieve(uploadId.toString()) ?: error("File not found")
            val changedPath = request.filename
            val contentType = ContentType.defaultForFileExtension(extension)
            storage.store(changedPath, original, contentType.toString())
            changedPath
        }

        dsl.transaction { tx ->
            uploadRepository.setSignedUrl(
                tx,
                storage.createSignedUrl(filename)!!,
                uploadId,
            )
            uploadRepository.notifyChange(tx, uploadId)
        }
    }

    private suspend fun convertUploadToPdf(
        uploadId: UUID,
        filename: String,
    ): ByteArray {
        val file = storage.retrieve(uploadId.toString()) ?: error("File not found")
        return gotenbergService.convertToPdf(
            FinishedUpload(
                file = file,
                originalFileExtension = File(filename).extension,
            ),
        )
    }

    fun preTerminate(
        request: HookRequest,
        personIdent: String,
    ): HookResponse =
        dsl.transactionResult { tx ->
            val correctOwner = uploadRepository.isOwnedByUser(tx, UUID.fromString(request.event.upload.id), personIdent)
            if (!correctOwner) {
                HookResponse(HTTPResponse(403), rejectTermination = true)
            } else {
                HookResponse()
            }
        }

    fun postTerminate(
        request: HookRequest,
        personIdent: String,
    ): HookResponse =
        dsl.transactionResult { tx ->
            val uploadId = UUID.fromString(request.event.upload.id)
            val correctOwner = uploadRepository.isOwnedByUser(tx, uploadId, personIdent)
            if (!correctOwner) {
                HookResponse(HTTPResponse(403), rejectTermination = true)
            }
            uploadRepository.deleteUpload(tx, uploadId)
            HookResponse(HTTPResponse(204))
        }

    suspend fun validateUpload(request: HookRequest): HookResponse {
        // TODO: Trenger vi å validere person?
        val validations = validator.validate(request)
        // Also filename
        val uploadId = request.event.upload.id
        val response =
            if (validations.isNotEmpty()) {
                dsl.transactionResult { tx ->
                    uploadRepository.addErrors(tx, UUID.fromString(uploadId), validations)
                }
                // TODO: Litt penere serialisering please
                HTTPResponse(
                    400,
                    mapOf("Content-Type" to "application/json"),
                    """{"errors": [${validations.joinToString(",") { """{"code": "${it.code}", "message": "${it.message}"}""" }}]}""",
                )
            } else {
                HTTPResponse()
            }
        return HookResponse(response)
    }
}
