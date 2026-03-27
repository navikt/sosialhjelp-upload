package no.nav.sosialhjelp.upload.pdf

import org.apache.xmpbox.schema.PDFAIdentificationSchema
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentCatalog
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDMetadata
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.xml.XmpSerializer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class PdfGenerator internal constructor(
    private var document: PDDocument,
) {
    private var currentPage = PDPage(PDRectangle.A4)
    private var currentStream: PDPageContentStream
    private var y: Float

    private var xmp: XMPMetadata = XMPMetadata.createXMPMetadata()
    private var pdfaid: PDFAIdentificationSchema = PDFAIdentificationSchema(xmp)

    private var colorProfile: InputStream? = this::class.java.getResourceAsStream("pdf/sRGB.icc")
    private var oi = PDOutputIntent(document, colorProfile)

    private var cat: PDDocumentCatalog = document.documentCatalog
    private var metadata: PDMetadata = PDMetadata(document)

    private var fontStream1: InputStream? = this::class.java.getResourceAsStream("pdf/SourceSansPro-Bold.ttf")
    private val fontBold: PDFont = PDType0Font.load(document, fontStream1)
    private var fontStream2: InputStream? = this::class.java.getResourceAsStream("pdf/SourceSansPro-Regular.ttf")
    private val fontPlain: PDFont = PDType0Font.load(document, fontStream2)

    private fun calculateStartY(): Float = MEDIA_BOX.upperRightY - MARGIN

    fun finish(): ByteArray {
        cat.metadata = metadata

        pdfaid.conformance = "B"
        pdfaid.part = 1
        pdfaid.setAboutAsSimple("")
        xmp.addSchema(pdfaid)
        val xmpOS = ByteArrayOutputStream()
        xmpOS.use {
            XmpSerializer().serialize(xmp, it, true)
            metadata.importXMPMetadata(it.toByteArray())
        }

        oi.info = S_RGB_IEC61966_2_1
        oi.outputCondition = S_RGB_IEC61966_2_1
        oi.outputConditionIdentifier = S_RGB_IEC61966_2_1
        oi.registryName = "http://www.color.org"
        cat.addOutputIntent(oi)

        document.addPage(currentPage)
        val baos = ByteArrayOutputStream()
        currentStream.close()
        document.save(baos)
        return baos.toByteArray()
    }

    fun addBlankLine() {
        y -= 20
    }

    fun addDividerLine() {
        currentStream.setLineWidth(1f)
        currentStream.moveTo(MARGIN, y)
        currentStream.lineTo(MEDIA_BOX.width - MARGIN, y)
        currentStream.closeAndStroke()
    }

    fun addSmallDividerLine() {
        currentStream.setLineWidth(1F)
        currentStream.moveTo((MARGIN * 7), y)
        currentStream.lineTo(MEDIA_BOX.width - (MARGIN * 7), y)
        currentStream.closeAndStroke()
    }

    fun addCenteredH1Bold(text: String) {
        addCenteredParagraph(text, fontBold, FONT_H1_SIZE, LEADING_PERCENTAGE)
    }

    fun addCenteredH4Bold(text: String) {
        addCenteredParagraph(text, fontBold, FONT_H4_SIZE, LEADING_PERCENTAGE)
    }

    fun addText(text: String) {
        addParagraph(text, fontPlain, FONT_PLAIN_SIZE, MARGIN)
    }

    private fun addParagraph(
        text: String,
        font: PDFont,
        fontSize: Float,
        margin: Float,
    ) {
        val lines: List<String> = parseLines(text, font, fontSize)
        currentStream.setFont(font, fontSize)
        currentStream.beginText()
        currentStream.newLineAtOffset(margin, y)

        lines.forEach {
            currentStream.setCharacterSpacing(0F)
            currentStream.showText(it)
            currentStream.newLineAtOffset(0F, -LEADING_PERCENTAGE * fontSize)

            y -= fontSize * LEADING_PERCENTAGE
            if (y < 100) {
                currentStream.endText()
                continueOnNewPage()
                y = calculateStartY()
                currentStream.beginText()
                currentStream.setFont(font, fontSize)
                currentStream.newLineAtOffset(margin, y)
            }
        }
        currentStream.endText()
    }

    private fun addCenteredParagraph(
        text: String,
        font: PDFont,
        fontSize: Float,
        leadingPercentage: Float,
    ) {
        val lines: List<String> = parseLines(text, font, fontSize)
        currentStream.beginText()
        currentStream.setFont(font, fontSize)
        var prevX = 0f
        for (i in lines.indices) {
            prevX =
                if (i == 0) {
                    val lineWidth = font.getStringWidth(lines[i]) / 1000 * fontSize
                    val startX = (MEDIA_BOX.width - lineWidth) / 2
                    currentStream.newLineAtOffset(startX, y)
                    startX
                } else {
                    val lineWidth = font.getStringWidth(lines[i]) / 1000 * fontSize
                    val startX = (MEDIA_BOX.width - lineWidth) / 2
                    currentStream.newLineAtOffset(startX - prevX, -leadingPercentage * fontSize)
                    startX
                }
            currentStream.showText(lines[i])
        }
        currentStream.endText()
        y -= lines.size * fontSize
    }

    private fun continueOnNewPage() {
        document.addPage(currentPage)
        currentStream.close()
        currentPage = PDPage(PDRectangle.A4)
        currentStream = PDPageContentStream(document, currentPage)
    }

    private fun parseLines(
        inputText: String,
        font: PDFont,
        fontSize: Float,
    ): List<String> {
        var text: String? = inputText
        val lines: MutableList<String> = ArrayList()
        var lastSpace = -1
        while (!text.isNullOrEmpty()) {
            var spaceIndex = text.indexOf(' ', lastSpace + 1)
            if (spaceIndex < 0) spaceIndex = text.length
            var subString = text.substring(0, spaceIndex)
            val size = fontSize * font.getStringWidth(subString) / 1000
            if (size > WIDTH_OF_CONTENT_COLUMN) {
                if (lastSpace < 0) {
                    lastSpace = spaceIndex
                }
                subString = text.substring(0, lastSpace)
                lines.add(subString)
                text = text.substring(lastSpace).trim { it <= ' ' }
                lastSpace = -1
            } else if (spaceIndex == text.length) {
                lines.add(text)
                text = ""
            } else {
                lastSpace = spaceIndex
            }
        }
        return lines
    }

    private fun addLogo() {
        val ximage = PDImageXObject.createFromByteArray(document, logo(), "logo")
        currentStream.drawImage(ximage, 27f, 765f, 99f, 62f)
    }

    private fun logo(): ByteArray {
        try {
            val inputStream = this::class.java.getResourceAsStream("/pdf/nav-logo_alphaless.jpg")
            if (inputStream != null) {
                return inputStream.use { it.readAllBytes() }
            }
        } catch (e: IOException) {
            // FIXME: Handle it
            e.printStackTrace()
        }
        return ByteArray(0)
    }

    companion object {
        private const val MARGIN = 40F

        private const val FONT_PLAIN_SIZE = 12F
        private const val FONT_H1_SIZE = 20F
        private const val FONT_H4_SIZE = 14F

        private const val LEADING_PERCENTAGE = 1.5F

        private const val S_RGB_IEC61966_2_1 = "sRGB IEC61966-2.1"

        private val WIDTH_OF_CONTENT_COLUMN = PDPage(PDRectangle.A4).mediaBox.width - (MARGIN * 2)
        private val MEDIA_BOX = PDPage(PDRectangle.A4).mediaBox
    }

    init {
        currentStream = PDPageContentStream(document, currentPage)
        y = calculateStartY()
        addLogo()
    }
}
