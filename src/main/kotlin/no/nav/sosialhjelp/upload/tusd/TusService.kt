package no.nav.sosialhjelp.upload.tusd

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
        if (extension !in listOf("pdf", "jpeg", "jpg", "png")) {
            val converted = convertUploadToPdf(request.uploadId, request.filename)
            val pdfName = File(request.filename).nameWithoutExtension + ".pdf"
            storage.store(pdfName, converted)
            dsl.transactionResult { it ->
                uploadRepository.updateConvertedFilename(it, pdfName, request.uploadId)
            }
        } else {
            // If we don't convert, we just need to copy the file to the original file name
            val original = storage.retrieve(request.uploadId.toString()) ?: error("File not found")
            val changedPath = request.filename
            storage.store(changedPath, original)
        }
        dsl.transactionResult { it -> uploadRepository.notifyChange(it, request.uploadId) }
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
        val response =
            if (validations.isNotEmpty()) {
                dsl.transactionResult { it ->
                    uploadRepository.addErrors(it, UUID.fromString(request.event.upload.id), validations)
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
