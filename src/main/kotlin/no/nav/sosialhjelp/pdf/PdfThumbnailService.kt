package no.nav.sosialhjelp.pdf

import io.ktor.server.application.*
import no.nav.sosialhjelp.database.PageRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class PdfThumbnailService(
    private val environment: ApplicationEnvironment,
) {
    val outputDir = environment.config.property("thumbnailer.outputDir").getString()
    val pageRepository = PageRepository()

    fun renderAndSaveThumbnails(
        uploadId: UUID,
        inputDocument: PDDocument,
        baseFilename: String,
    ) {
        val pdfRenderer = PDFRenderer(inputDocument)

        for (pageIndex in 0..inputDocument.numberOfPages) {
            val thumbnail =
                try {
                    writeThumbnail(pdfRenderer, baseFilename, pageIndex)
                } catch (e: Exception) {
                    environment.log.error("failed to write thumbnail", e)
                    throw e
                }

            try {
                transaction { pageRepository.setFilename(uploadId, pageIndex, thumbnail) }
            } catch (e: Exception) {
                environment.log.error("failed to update thumbnail filename in database", e)
                throw e
            }
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
