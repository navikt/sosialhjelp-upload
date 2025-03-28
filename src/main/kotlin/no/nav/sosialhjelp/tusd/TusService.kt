package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.server.application.*
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.UploadedFileSpec
import no.nav.sosialhjelp.database.PageRepository
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.database.schema.DocumentTable.getOrCreateDocument
import no.nav.sosialhjelp.pdf.GotenbergService
import no.nav.sosialhjelp.pdf.PdfThumbnailService
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
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
    val pageRepository = PageRepository()
    val fileFactory = FileFactory(environment)

    fun preCreate(request: HookRequest): HookResponse {
        require(request.Type == HookType.PreCreate)
        val uploadFileSpec = UploadedFileSpec.fromFilename(request.Event.Upload.MetaData.filename)
        val documentId = transaction { getOrCreateDocument(DocumentIdent.fromRequest(request.Event.Upload.MetaData)) }
        val uploadId = transaction { uploadRepository.create(documentId, uploadFileSpec) }
        environment.log.info("Creating a new upload, ID: $uploadId for document ${documentId.value}")
        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    fun postCreate(request: HookRequest) {
        require(request.Type == HookType.PostCreate)
    }

    suspend fun postFinish(request: HookRequest) {
        require(request.Type == HookType.PostFinish)
        val uploadId = UUID.fromString(request.Event.Upload.ID)

        convertUploadToPdf(uploadId).let { uploadPdf ->
            Loader
                .loadPDF(uploadPdf)
                .also { pageRepository.setPageCount(uploadId, it.numberOfPages) }
                .also { pdfThumbnailService.renderAndSaveThumbnails(uploadId, it, uploadPdf.nameWithoutExtension) }
        }

        uploadRepository.notifyChange(uploadId)
    }

    private suspend fun convertUploadToPdf(uploadId: UUID): File =
        fileFactory.uploadMainFile(uploadId).also {
            FinishedUpload(
                fileFactory.uploadSourceFile(uploadId),
                transaction { UploadedFileSpec.fromFilename(uploadRepository.getFilenameById(uploadId)).extension },
            ).also { upload -> it.writeBytes(gotenbergService.convertToPdf(upload)) }
        }
}
