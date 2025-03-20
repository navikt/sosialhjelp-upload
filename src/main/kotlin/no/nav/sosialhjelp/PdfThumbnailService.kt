package no.nav.sosialhjelp

import io.ktor.server.application.*
import no.nav.sosialhjelp.schema.PageTable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
        val pdfRenderer = PDFRenderer(inputDocument)
        database.setPageCount(uploadId, inputDocument.numberOfPages)
        for (pageIndex in 0 until inputDocument.numberOfPages) {
            val filename =
                writeThumbnail(
                    pdfRenderer = pdfRenderer,
                    baseFilename = inputPdf.nameWithoutExtension,
                    pageIndex = pageIndex,
                )
            transaction {
                PageTable
                    .update({ PageTable.upload eq uploadId and (PageTable.pageNumber eq pageIndex) }) {
                        it[PageTable.filename] = filename
                    }
            }
        }
    }

    private fun renderPage(
        pdfRenderer: PDFRenderer,
        page: Int,
    ): BufferedImage = pdfRenderer.renderImageWithDPI(page, 300f, ImageType.RGB)

    private fun writeThumbnail(
        pdfRenderer: PDFRenderer,
        baseFilename: String,
        pageIndex: Int,
    ): String {
        val image = renderPage(pdfRenderer, pageIndex)
        val thumbnailFile = makeOutputFile(baseFilename, pageIndex)

        val status = ImageIO.write(image, "JPEG", thumbnailFile)
        if (!status) {
            error("Could not write image to $baseFilename")
        }
        return thumbnailFile.name
    }

    private fun makeOutputFile(
        inputFilename: String,
        pageIndex: Int,
    ) = File(outputDir, String.format("%s-%04d.jpg", inputFilename, pageIndex + 1))
}
