package no.nav.sosialhjelp.upload.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PdfFil(val filnavn: String)

data class PdfMetadata(
    val type: String,
    val filer: List<PdfFil>,
)

object EttersendelsePdfGenerator {
    fun generate(
        metadata: PdfMetadata,
        fodselsnummer: String,
    ): ByteArray =
        try {
            PDDocument().use { document ->
                val pdf = PdfGenerator(document)

                pdf.addCenteredH1Bold("Ettersendelse av vedlegg")
                pdf.addCenteredH1Bold("Søknad om økonomisk sosialhjelp")
                pdf.addBlankLine()
                pdf.addSmallDividerLine()
                pdf.addBlankLine()
                pdf.addCenteredH4Bold(fodselsnummer)
                pdf.addSmallDividerLine()

                pdf.addBlankLine()

                pdf.addText("Følgende vedlegg er sendt " + formatLocalDateTime(LocalDateTime.now()))

                pdf.addBlankLine()
                pdf.addText("Type: " + metadata.type.replaceUnsupportedCharacters())
                metadata.filer.forEach { fil ->
                    pdf.addText("Filnavn: " + fil.filnavn)
                }

                pdf.finish()
            }
        } catch (e: Exception) {
            throw RuntimeException("Error while creating pdf", e)
        }
}

/** Replace illegal characters bacause there is no glyph for that in the font we use (SourceSansPro-Regular):
 * - U+0009: Tab character (\t)
 * - U+000D: Carriage return (CR)
 * - U+F0B7: Bullet point?
 * - U+001F: No idea
 * - U+000A: EOF/LF/NL
 **/
private fun String.replaceUnsupportedCharacters() =
    replace(Regex("[\\x09\\x0D\\x0A]"), " ")
        .replace(Regex("[\\uF0B7\\x1F]"), "")

fun formatLocalDateTime(dato: LocalDateTime): String {
    val datoFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy 'kl.' HH.mm", Locale.forLanguageTag("nb"))
    return dato.format(datoFormatter)
}
