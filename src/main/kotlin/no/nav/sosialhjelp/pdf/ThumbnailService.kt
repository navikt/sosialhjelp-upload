package no.nav.sosialhjelp.pdf

import io.ktor.server.application.*
import no.nav.sosialhjelp.database.DocumentChangeNotifier
import no.nav.sosialhjelp.database.PageRepository
import no.nav.sosialhjelp.database.UploadRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class ThumbnailService(
    environment: ApplicationEnvironment,
) {
    val outputDir = environment.config.property("thumbnailer.outputDir").getString()
    val pageRepository = PageRepository()
    val uploadRepository = UploadRepository()

    suspend fun makeThumbnails(
        uploadId: UUID,
        inputDocument: PDDocument,
        baseFilename: String,
    ) {
        newSuspendedTransaction {
            for (pageIndex in 0..inputDocument.numberOfPages - 1) pageRepository.createEmptyPage(uploadId, pageIndex)
            DocumentChangeNotifier.notifyChange(uploadRepository.getDocumentIdFromUploadId(uploadId).value)
        }

        val pdfRenderer = PDFRenderer(inputDocument)

        for (pageIndex in 0..inputDocument.numberOfPages - 1) {
            val thumbnail = writeThumbnail(pdfRenderer, baseFilename, pageIndex)
            newSuspendedTransaction { pageRepository.setFilename(uploadId, pageIndex, thumbnail) }
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
