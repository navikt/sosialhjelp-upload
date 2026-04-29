package no.nav.sosialhjelp.upload.validation

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBuffer
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.tika.Tika
import org.slf4j.LoggerFactory
import java.io.IOException
import java.text.Normalizer

// 10 MB
const val MAX_FILE_SIZE = 10 * 1024 * 1024

val SUPPORTED_MIME_TYPES =
    setOf(
        // PDF
        "application/pdf",
        // Word Processing
        // .doc
        "application/msword",
        // .docx, .docm, .dotx, .dotm
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-word.document.macroEnabled.12",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.ms-word.template.macroEnabled.12",
        // .dot
        "application/msword-template",
        // .odt, .fodt, .ott
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.text-flat-xml",
        "application/vnd.oasis.opendocument.text-template",
        // .rtf
        "application/rtf",
        "text/rtf",
        // .txt
        "text/plain",
        // .wpd
        "application/wordperfect",
        "application/vnd.wordperfect",
        // .pages (Apple Pages - zip-based)
        // .abw (AbiWord)
        "application/x-abiword",
        // .hwp (Hangul Word Processor)
        "application/x-hwp",
        // .wps (Microsoft Works)
        "application/vnd.ms-works",
        // .lwp (Lotus Word Pro)
        "application/vnd.lotus-wordpro",
        // .sxw, .stw, .sgl (StarOffice Writer)
        "application/vnd.sun.xml.writer",
        "application/vnd.sun.xml.writer.template",
        "application/vnd.sun.xml.writer.global",
        // .vor (StarOffice template)
        "application/vnd.stardivision.writer",
        // .uof (Uniform Office Format)
        "application/vnd.uoml+xml",
        // .xml (generic XML)
        "application/xml",
        "text/xml",
        // .psw (Pocket Word)
        "application/x-pocket-word",
        // Spreadsheets
        // .xls
        "application/vnd.ms-excel",
        // .xlsx, .xltx
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        // .xlsm, .xltm
        "application/vnd.ms-excel.sheet.macroEnabled.12",
        "application/vnd.ms-excel.template.macroEnabled.12",
        // .xlsb
        "application/vnd.ms-excel.sheet.binary.macroEnabled.12",
        // .xlw
        "application/vnd.ms-excel-workspace",
        // .ods, .fods, .ots
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.spreadsheet-flat-xml",
        "application/vnd.oasis.opendocument.spreadsheet-template",
        // .csv
        "text/csv",
        // .numbers (Apple Numbers - zip-based)
        // .123, .wk1, .wks (Lotus 1-2-3)
        "application/vnd.lotus-1-2-3",
        // .wb2 (Quattro Pro)
        "application/x-quattro-pro",
        // .dbf (dBASE)
        "application/dbase",
        "application/x-dbase",
        // .dif (Data Interchange Format)
        "application/x-dif",
        "text/x-dif",
        // .slk (SYLK)
        "application/x-sylk",
        "text/spreadsheet",
        // .sxc, .stc (StarOffice Calc)
        "application/vnd.sun.xml.calc",
        "application/vnd.sun.xml.calc.template",
        // .uos (Uniform Office Spreadsheet)
        "application/vnd.uoml+xml",
        // .pxl (Pocket Excel)
        "application/x-pocket-excel",
        // .sdc (StarOffice Calc legacy)
        "application/vnd.stardivision.calc",
        // Presentations
        // .ppt
        "application/vnd.ms-powerpoint",
        // .pptx, .potx
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        // .pptm, .potm
        "application/vnd.ms-powerpoint.presentation.macroEnabled.12",
        "application/vnd.ms-powerpoint.template.macroEnabled.12",
        // .pot
        "application/vnd.ms-powerpoint-template",
        // .pps
        "application/vnd.ms-powerpoint-slideshow",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        // .odp, .fodp, .otp
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.presentation-flat-xml",
        "application/vnd.oasis.opendocument.presentation-template",
        // .key (Apple Keynote - zip-based)
        // .sxi, .sti (StarOffice Impress)
        "application/vnd.sun.xml.impress",
        "application/vnd.sun.xml.impress.template",
        // .uop
        "application/vnd.uoml+xml",
        // .sdd, .sdp (StarOffice Impress legacy)
        "application/vnd.stardivision.impress",
        // Graphics & Drawing
        // .odg, .fodg, .otg
        "application/vnd.oasis.opendocument.graphics",
        "application/vnd.oasis.opendocument.graphics-flat-xml",
        "application/vnd.oasis.opendocument.graphics-template",
        // .vsd, .vsdx, .vsdm, .vdx (Visio)
        "application/vnd.visio",
        "application/vnd.ms-visio.drawing",
        "application/vnd.ms-visio.drawing.macroEnabled",
        "application/vnd.ms-visio.xml",
        // .cdr (CorelDRAW)
        "application/coreldraw",
        "image/x-cdr",
        // .svg
        "image/svg+xml",
        // .svm (StarView Metafile)
        "image/x-svm",
        // .wmf
        "image/wmf",
        "image/x-wmf",
        // .emf
        "image/emf",
        "image/x-emf",
        // .cgm (Computer Graphics Metafile)
        "image/cgm",
        // .dxf (AutoCAD)
        "image/vnd.dxf",
        "application/dxf",
        // .std, .sxd (StarOffice Draw)
        "application/vnd.sun.xml.draw",
        "application/vnd.sun.xml.draw.template",
        // .pub (Microsoft Publisher)
        "application/vnd.ms-publisher",
        "application/x-mspublisher",
        // .wpg (WordPerfect Graphics)
        "image/x-wpg",
        // .sda (StarOffice Draw legacy)
        "application/vnd.stardivision.draw",
        // .met (OS/2 Metafile)
        "image/x-met",
        // .cmx (Corel Metafile Exchange)
        "image/x-cmx",
        // .eps
        "application/postscript",
        "image/x-eps",
        // Images
        "image/jpeg",
        "image/png",
        "image/bmp",
        "image/gif",
        "image/tiff",
        // .pbm, .pgm, .ppm (Netpbm)
        "image/x-portable-bitmap",
        "image/x-portable-graymap",
        "image/x-portable-pixmap",
        "image/x-portable-anymap",
        // .xbm
        "image/x-xbitmap",
        // .xpm
        "image/x-xpixmap",
        // .pcx
        "image/x-pcx",
        // .pcd (Kodak Photo CD)
        "image/x-photo-cd",
        // .pct / .pict (Mac PICT)
        "image/x-pict",
        // .psd (Photoshop)
        "image/vnd.adobe.photoshop",
        // .tga
        "image/x-tga",
        // .ras (Sun Raster)
        "image/x-sun-raster",
        // .pwp (PhotoWorks)
        "image/x-pwp",
        // Web & Other
        "text/html",
        "application/xhtml+xml",
        // .epub
        "application/epub+zip",
        // .pdb (Palm Database)
        "application/x-palm-database",
        // .ltx (LaTeX)
        "application/x-latex",
        "text/x-tex",
        // .mml (MathML)
        "application/mathml+xml",
        // .smf, .sxm (StarOffice Math)
        "application/vnd.sun.xml.math",
        "application/vnd.stardivision.math",
        // .sxg, .oth, .odm (StarOffice/ODF master documents)
        "application/vnd.sun.xml.writer.global",
        "application/vnd.oasis.opendocument.text-web",
        "application/vnd.oasis.opendocument.text-master",
        // .swf (Flash - legacy)
        "application/x-shockwave-flash",
        // Tika generic type for zip-based Office formats (e.g. .docx, .xlsx, .pptx)
        "application/zip",
        "application/x-tika-ooxml",
    )

class UploadValidator(
    val virusScanner: VirusScanner,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val logger = LoggerFactory.getLogger(this::class.java)
    private val tika = Tika()

    suspend fun validate(
        filename: String,
        data: ByteArray,
        fileSize: Long,
    ): List<Validation> =
        coroutineScope {
            val virusScanValidation = async(ioDispatcher) { runVirusScan(data) }
            val (mimeType, fileTypeValidation) = validateFileType(data)
            listOfNotNull(
                validateFileSize(fileSize),
                validateFilename(Filename(filename)),
                fileTypeValidation,
                if (mimeType == "application/pdf") validatePdf(data) else null,
                virusScanValidation.await(),
            )
        }

    private suspend fun validatePdf(data: ByteArray): Validation? =
        withContext(ioDispatcher) {
            try {
                val randomAccessRead = RandomAccessReadBuffer(data.inputStream())
                Loader
                    .loadPDF(randomAccessRead)
                    .use { document ->
                        if (document.isEncrypted) {
                            EncryptedPdfValidation()
                        } else {
                            null
                        }
                    }
            } catch (e: InvalidPasswordException) {
                logger.warn(ValidationCode.ENCRYPTED_PDF.name + " " + e.message)
                EncryptedPdfValidation()
            } catch (e: IOException) {
                logger.warn(ValidationCode.INVALID_PDF.name, e)
                InvalidPdfValidation()
            } catch (e: Exception) {
                logger.warn(ValidationCode.INVALID_PDF.name + " " + e.message, e)
                InvalidPdfValidation()
            }
        }

    private suspend fun validateFileType(data: ByteArray): Pair<String, Validation?> =
        withContext(ioDispatcher) {
            val mimeType = tika.detect(data.inputStream())
            if (mimeType !in SUPPORTED_MIME_TYPES) {
                return@withContext mimeType to FileTypeValidation(actual = mimeType)
            }
            return@withContext mimeType to null
        }

    private suspend fun runVirusScan(data: ByteArray): Validation? =
        when (virusScanner.scan(data)) {
            Result.FOUND -> VirusValidation()
            Result.ERROR -> {
                logger.warn("Virus scanner returned ERROR, treating file as clean")
                null
            }
            Result.OK -> null
        }

    private fun validateFileSize(filesize: Long): Validation? {
        if (filesize > MAX_FILE_SIZE) {
            return FileSizeValidation()
        }
        return null
    }

    private fun validateFilename(filename: Filename): Validation? {
        if (filename.containsIllegalCharacters()) {
            return FilenameValidation()
        }
        return null
    }
}

@JvmInline
private value class Filename(
    val value: String,
) {
    fun sanitize() = Normalizer.normalize(value, Normalizer.Form.NFC).trim()

    fun containsIllegalCharacters(): Boolean = this.sanitize().contains("[^a-zæøåA-ZÆØÅ0-9 (),._–-]".toRegex())
}
