package no.nav.sosialhjelp

import io.ktor.server.application.*
import no.nav.sosialhjelp.schema.PageEntity
import no.nav.sosialhjelp.schema.PageTable
import no.nav.sosialhjelp.schema.UploadEntity
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

class PdfThumbnailService(
    environment: ApplicationEnvironment,
) {
    val database = PdfThumbnailDatabase()
    val outputDir = environment.config.property("thumbnailer.outputDir").getString()

    fun makeThumbnails(
        upload: UploadEntity,
        inputPdf: File,
    ) {
        val inputDocument = Loader.loadPDF(inputPdf)
        val pdfRenderer = PDFRenderer(inputDocument)
        database.setPageCount(upload.id.value, inputDocument.numberOfPages)
        for (pageIndex in 0 until inputDocument.numberOfPages) {
            val file = makeOutputFile(inputPdf.nameWithoutExtension, pageIndex)
            writeThumbnail(
                image = renderPage(pdfRenderer, pageIndex),
                file = file,
            )
            transaction {
                val page = PageEntity.find { PageTable.upload eq upload.id.value and (PageTable.pageNumber eq pageIndex) }.first()
                PageEntity.findByIdAndUpdate(page.id.value) { it.filename = file.name }
                database.notifyChange(upload.document.soknadId, upload.document.vedleggType)
            }
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
    ) = File(outputDir, String.format("%s-%04d.jpg", inputFilename, pageIndex + 1))
}
