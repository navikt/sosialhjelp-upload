@file:Suppress("TooManyFunctions")

package no.nav.sosialhjelp.upload.pdf

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
import org.apache.xmpbox.schema.PDFAIdentificationSchema
import org.apache.xmpbox.xml.XmpSerializer
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

class PdfGenerator internal constructor(
    private var document: PDDocument,
) {
    private val logger = LoggerFactory.getLogger(PdfGenerator::class.java)
    private var currentPage = PDPage(PDRectangle.A4)
    private var currentStream: PDPageContentStream
    private var y: Float

    private var xmp: XMPMetadata = XMPMetadata.createXMPMetadata()
    private var pdfaid: PDFAIdentificationSchema = PDFAIdentificationSchema(xmp)

    private var colorProfile: InputStream? = this::class.java.getResourceAsStream("/pdf/sRGB.icc")
    private var oi = PDOutputIntent(document, colorProfile)

    private var cat: PDDocumentCatalog = document.documentCatalog
    private var metadata: PDMetadata = PDMetadata(document)

    private var fontStream1: InputStream? = this::class.java.getResourceAsStream("/pdf/NotoSans-Bold.ttf")
    private val fontBold: PDFont = PDType0Font.load(document, fontStream1)
    private var fontStream2: InputStream? = this::class.java.getResourceAsStream("/pdf/NotoSans-Regular.ttf")
    private val fontPlain: PDFont = PDType0Font.load(document, fontStream2)
    private var fontStream3: InputStream? = this::class.java.getResourceAsStream("/pdf/NotoNaskhArabic-Bold.ttf")
    private val fontArabicBold: PDFont = PDType0Font.load(document, fontStream3)
    private var fontStream4: InputStream? = this::class.java.getResourceAsStream("/pdf/NotoNaskhArabic-Regular.ttf")
    private val fontArabicPlain: PDFont = PDType0Font.load(document, fontStream4)

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
        addCenteredParagraph(text, fontBold, fontArabicBold, FONT_H1_SIZE, LEADING_PERCENTAGE)
    }

    fun addCenteredH4Bold(text: String) {
        addCenteredParagraph(text, fontBold, fontArabicBold, FONT_H4_SIZE, LEADING_PERCENTAGE)
    }

    fun addText(text: String) {
        addParagraph(text, fontPlain, fontArabicPlain, FONT_PLAIN_SIZE, MARGIN)
    }

    private fun addParagraph(
        text: String,
        font: PDFont,
        arabicFont: PDFont,
        fontSize: Float,
        margin: Float,
    ) {
        val lines: List<String> = parseLines(text, font, arabicFont, fontSize)
        currentStream.beginText()
        currentStream.newLineAtOffset(margin, y)

        lines.forEach { line ->
            var xOffset = 0f
            splitIntoFontRuns(line).forEach { (run, isArabic) ->
                val activeFont = if (isArabic) arabicFont else font
                currentStream.setFont(activeFont, fontSize)
                currentStream.setCharacterSpacing(0F)
                currentStream.showText(run)
                xOffset += activeFont.getStringWidth(run) / 1000 * fontSize
            }
            currentStream.newLineAtOffset(-xOffset, -LEADING_PERCENTAGE * fontSize)

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
        arabicFont: PDFont,
        fontSize: Float,
        leadingPercentage: Float,
    ) {
        val lines: List<String> = parseLines(text, font, arabicFont, fontSize)
        currentStream.beginText()
        var prevX = 0f
        for (i in lines.indices) {
            val lineWidth = measureLineWidth(lines[i], font, arabicFont, fontSize)
            val startX = (MEDIA_BOX.width - lineWidth) / 2
            if (i == 0) {
                currentStream.newLineAtOffset(startX, y)
            } else {
                currentStream.newLineAtOffset(startX - prevX, -leadingPercentage * fontSize)
            }
            prevX = startX
            splitIntoFontRuns(lines[i]).forEach { (run, isArabic) ->
                currentStream.setFont(if (isArabic) arabicFont else font, fontSize)
                currentStream.showText(run)
            }
        }
        currentStream.endText()
        y -= lines.size * fontSize
    }

    /** Measures the total rendered width of a line, accounting for font switching. */
    private fun measureLineWidth(
        line: String,
        font: PDFont,
        arabicFont: PDFont,
        fontSize: Float,
    ): Float =
        splitIntoFontRuns(line).sumOf { (run, isArabic) ->
            ((if (isArabic) arabicFont else font).getStringWidth(run) / 1000 * fontSize).toDouble()
        }.toFloat()

    /**
     * Splits a string into runs of consecutive characters that share the same script family:
     * Arabic (U+0600–U+06FF, U+0750–U+077F, U+FB50–U+FDFF, U+FE70–U+FEFF, U+1EE00–U+1EEFF)
     * versus everything else (Latin, Norwegian, digits, punctuation…).
     *
     * Returns a list of (text, isArabic) pairs in logical order.
     */
    private fun splitIntoFontRuns(text: String): List<Pair<String, Boolean>> {
        if (text.isEmpty()) return emptyList()
        val runs = mutableListOf<Pair<String, Boolean>>()
        val sb = StringBuilder()
        var currentIsArabic = text[0].isArabicScript()
        for (ch in text) {
            val arabic = ch.isArabicScript()
            if (arabic != currentIsArabic) {
                runs.add(sb.toString() to currentIsArabic)
                sb.clear()
                currentIsArabic = arabic
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty()) runs.add(sb.toString() to currentIsArabic)
        return runs
    }

    private fun Char.isArabicScript(): Boolean {
        val cp = this.code
        return cp in 0x0600..0x06FF || // Arabic
            cp in 0x0750..0x077F || // Arabic Supplement
            cp in 0xFB50..0xFDFF || // Arabic Presentation Forms-A
            cp in 0xFE70..0xFEFF // Arabic Presentation Forms-B
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
        arabicFont: PDFont,
        fontSize: Float,
    ): List<String> {
        var text: String? = inputText
        val lines: MutableList<String> = ArrayList()
        var lastSpace = -1
        while (!text.isNullOrEmpty()) {
            var spaceIndex = text.indexOf(' ', lastSpace + 1)
            if (spaceIndex < 0) spaceIndex = text.length
            val subString = text.substring(0, spaceIndex)
            val size = measureLineWidth(subString, font, arabicFont, fontSize)
            if (size > WIDTH_OF_CONTENT_COLUMN) {
                if (lastSpace < 0) {
                    lastSpace = spaceIndex
                }
                lines.add(text.substring(0, lastSpace))
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
            logger.error("Failed to load NAV logo from resources", e)
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
