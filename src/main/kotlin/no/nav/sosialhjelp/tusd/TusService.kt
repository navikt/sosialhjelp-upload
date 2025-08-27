package no.nav.sosialhjelp.tusd

import DocumentRepository
import io.ktor.server.application.*
import kotlinx.coroutines.Dispatchers
import no.nav.sosialhjelp.common.FilePathFactory
import no.nav.sosialhjelp.common.FinishedUpload
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import no.nav.sosialhjelp.pdf.ThumbnailService
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import no.nav.sosialhjelp.tusd.input.CreateUploadRequest
import no.nav.sosialhjelp.tusd.input.PostFinishRequest
import org.apache.pdfbox.Loader
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.io.File
import java.util.*

class TusService(
    val environment: ApplicationEnvironment,
) {
    val gotenbergService = GotenbergService(environment)
    val thumbnailService = ThumbnailService(environment)
    val uploadRepository = UploadRepository()
    val documentRepository = DocumentRepository()
    val filePathFactory = FilePathFactory.fromEnvironment(environment)

    suspend fun preCreate(
        request: HookRequest,
        personident: String,
    ): HookResponse {
        val uploadRequest = CreateUploadRequest.fromRequest(request)

        val uploadId =
            suspendTransaction(Dispatchers.IO) {
                val documentId = documentRepository.getOrCreateDocument(uploadRequest.documentIdent, personident)
                val uploadId = uploadRepository.create(documentId, uploadRequest.filename).value
                environment.log.info("Creating a new upload, ID: $uploadId for document ${documentId.value}")
                uploadId
            }

        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    suspend fun postFinish(request: HookRequest) {
        val request = PostFinishRequest.fromRequest(request)
        val uploadPdf = convertUploadToPdf(request.uploadId, request.filename)
        thumbnailService.makeThumbnails(request.uploadId, Loader.loadPDF(uploadPdf), request.filename)
        uploadRepository.notifyChange(request.uploadId)
    }

    private suspend fun convertUploadToPdf(
        uploadId: UUID,
        filename: String,
    ): File =
        File(filePathFactory.getConvertedPdfPath(uploadId).toString()).also { file ->
            file.writeBytes(
                gotenbergService.convertToPdf(
                    FinishedUpload(
                        file = File(filePathFactory.getOriginalUploadPath(uploadId).toString()),
                        originalFileExtension = File(filename).extension,
                    ),
                ),
            )
        }
}
