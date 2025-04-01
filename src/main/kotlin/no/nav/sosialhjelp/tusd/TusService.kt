package no.nav.sosialhjelp.tusd

import DocumentRepository
import HookType
import io.ktor.server.application.*
import no.nav.sosialhjelp.common.FileFactory
import no.nav.sosialhjelp.common.FinishedUpload
import no.nav.sosialhjelp.common.UploadedFileSpec
import no.nav.sosialhjelp.database.PageRepository
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import no.nav.sosialhjelp.pdf.PdfThumbnailService
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import no.nav.sosialhjelp.tusd.input.CreateUploadRequest
import org.apache.pdfbox.Loader
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

class TusService(
    val environment: ApplicationEnvironment,
) {
    val gotenbergService = GotenbergService(environment)
    val pdfThumbnailService = PdfThumbnailService(environment)
    val uploadRepository = UploadRepository()
    val documentRepository = DocumentRepository()
    val pageRepository = PageRepository()
    val fileFactory = FileFactory(environment)

    fun preCreate(
        request: HookRequest,
        personident: String,
    ): HookResponse {
        val uploadRequest = CreateUploadRequest.fromRequest(request)
        val documentId = transaction { documentRepository.getOrCreateDocument(uploadRequest.documentIdent, personident) }
        val uploadId = transaction { uploadRepository.create(documentId, uploadRequest.uploadFileSpec) }
        environment.log.info("Creating a new upload, ID: $uploadId for document ${documentId.value}")
        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    fun postCreate(request: HookRequest) {
        require(request.Type == HookType.PostCreate)
    }

    suspend fun postFinish(request: HookRequest) {
        require(request.Type == HookType.PostFinish)
        UUID.fromString(request.Event.Upload.ID).also { uploadId ->
            convertUploadToPdf(uploadId).let { uploadPdf ->
                Loader
                    .loadPDF(uploadPdf)
                    .also { pageRepository.setPageCount(uploadId, it.numberOfPages) }
                    .also { pdfThumbnailService.renderAndSaveThumbnails(uploadId, it, uploadPdf.nameWithoutExtension) }
            }
            uploadRepository.notifyChange(uploadId)
        }
    }

    private suspend fun convertUploadToPdf(uploadId: UUID): File =
        fileFactory.uploadPdfFile(uploadId).also {
            FinishedUpload(
                fileFactory.uploadSourceFile(uploadId),
                transaction { UploadedFileSpec.fromFilename(uploadRepository.getFilenameById(uploadId)).extension },
            ).also { upload -> it.writeBytes(gotenbergService.convertToPdf(upload)) }
        }
}
