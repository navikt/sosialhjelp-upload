package no.nav.sosialhjelp.upload.tusd

import no.nav.sosialhjelp.upload.database.DocumentRepository
import io.ktor.util.logging.Logger
import kotlinx.coroutines.Dispatchers
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.common.FinishedUpload
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import no.nav.sosialhjelp.upload.pdf.ThumbnailService
import no.nav.sosialhjelp.upload.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.upload.tusd.dto.HookRequest
import no.nav.sosialhjelp.upload.tusd.dto.HookResponse
import no.nav.sosialhjelp.upload.tusd.input.CreateUploadRequest
import no.nav.sosialhjelp.upload.tusd.input.PostFinishRequest
import org.apache.pdfbox.Loader
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.io.File
import java.util.*

class TusService(
    val logger: Logger,
    val uploadRepository: UploadRepository,
    val thumbnailService: ThumbnailService,
    val gotenbergService: GotenbergService,
    val documentRepository: DocumentRepository,
    val filePathFactory: FilePathFactory,
    val dsl: DSLContext,
) {

    suspend fun preCreate(
        request: HookRequest,
        personident: String,
    ): HookResponse {
        val uploadRequest = CreateUploadRequest.fromRequest(request)

        val uploadId =
            dsl.transactionCoroutine(Dispatchers.IO) {
                val documentId = documentRepository.getOrCreateDocument(it, uploadRequest.externalId, personident)
                val uploadId = uploadRepository.create(it, documentId, uploadRequest.filename)
                logger.info("Creating a new upload, ID: $uploadId for document $documentId")
                uploadId
            }

        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    suspend fun postFinish(request: HookRequest) {
        val request = PostFinishRequest.fromRequest(request)
        val uploadPdf = convertUploadToPdf(request.uploadId, request.filename)
        thumbnailService.makeThumbnails(request.uploadId, Loader.loadPDF(uploadPdf), request.filename)
        dsl.transactionCoroutine(Dispatchers.IO) { uploadRepository.notifyChange(it, request.uploadId) }
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
