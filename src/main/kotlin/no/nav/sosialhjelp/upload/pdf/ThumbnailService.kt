package no.nav.sosialhjelp.upload.pdf

import io.ktor.server.plugins.di.annotations.Property
import kotlinx.coroutines.Dispatchers
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class ThumbnailService(
    val pageRepository: PageRepository,
    val uploadRepository: UploadRepository,
    val dsl: DSLContext,
    @Property("thumbnailer.outputDir") val outputDir: String
) {

    suspend fun makeThumbnails(
        uploadId: UUID,
        inputDocument: PDDocument,
        baseFilename: String,
    ) {
        dsl.transactionCoroutine(Dispatchers.IO) {
            for (pageIndex in 0..<inputDocument.numberOfPages) pageRepository.createEmptyPage(it, uploadId, pageIndex)
            val documentId = uploadRepository.getDocumentIdFromUploadId(it, uploadId)
            documentId?.let { DocumentChangeNotifier.notifyChange(documentId) }
        }

        val pdfRenderer = PDFRenderer(inputDocument)

        for (pageIndex in 0..<inputDocument.numberOfPages) {
            val thumbnail = writeThumbnail(pdfRenderer, baseFilename, pageIndex)
            dsl.transactionCoroutine(Dispatchers.IO) { pageRepository.setFilename(it, uploadId, pageIndex, thumbnail) }
        }
    }

    private fun writeThumbnail(
        pdfRenderer: PDFRenderer,
        baseFilename: String,
        pageIndex: Int,
    ): File {
        val image = pdfRenderer.renderImageWithDPI(pageIndex, 300f, ImageType.RGB)
        val thumbnailFile = File(outputDir, String.format("%s-%04d.jpg", baseFilename, pageIndex + 1))

        if (!ImageIO.write(image, "JPEG", thumbnailFile)) {
            error("Could not write image to $baseFilename")
        }

        return thumbnailFile
    }
}
