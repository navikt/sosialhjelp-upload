package no.nav.sosialhjelp

import io.ktor.server.application.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.image.BufferedImage
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class PdfThumbnailService(
    environment: ApplicationEnvironment,
) {
    val database = PdfThumbnailDatabase()
    val outputDir = environment.config.property("thumbnailer.outputDir").getString()

    fun makeThumbnails(
        uploadId: UUID,
        inputPdf: File,
    ) {
        val inputDocument = Loader.loadPDF(inputPdf)
        database.setPageCount(uploadId, inputDocument.numberOfPages)
        renderThumbnails(inputDocument, inputPdf.nameWithoutExtension)
    }

    private fun renderThumbnails(
        document: PDDocument,
        originalFilename: String,
    ) = PDFRenderer(document).let { pdfRenderer ->
        for (pageIndex in 0 until document.numberOfPages) {
            writeThumbnail(
                image = renderPage(pdfRenderer, pageIndex),
                file = makeOutputFile(originalFilename, pageIndex),
            )
        }
    }

    private fun renderPage(
        pdfRenderer: PDFRenderer,
        page: Int,
    ): BufferedImage = pdfRenderer.renderImageWithDPI(page, 300f, ImageType.RGB)

    private fun writeThumbnail(
        image: BufferedImage,
        file: File,
    ) = ImageIO.write(image, "JPEG", file)

    private fun makeOutputFile(
        inputFilename: String,
        pageIndex: Int,
    ) = File(outputDir, String.format("%s-%04d", inputFilename, pageIndex + 1))
}
