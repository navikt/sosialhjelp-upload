package no.nav.sosialhjelp
import io.ktor.server.application.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.File
import javax.imageio.ImageIO

class PdfThumbnailService(
    val environment: ApplicationEnvironment,
) {
    fun makeThumbnails(inputPdf: File) {
        val outputDir = "./tusd-data" // Output directory

        check(File(outputDir).exists())

        val document = Loader.loadPDF(inputPdf)
        val pdfRenderer = PDFRenderer(document)

        for (page in 0 until document.numberOfPages) {
            val outputFileName = inputPdf.nameWithoutExtension
            val pageNumber = String.format("%04d", page + 1)
            val outputFile = File("$outputDir/$outputFileName-$pageNumber.jpg")

            ImageIO.write(pdfRenderer.renderImageWithDPI(page, 300f, ImageType.RGB), "JPEG", outputFile)
            environment.log.info("Wrote page ${page + 1}/${document.numberOfPages} to $outputFileName")
        }
    }
}
