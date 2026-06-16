@file:Suppress("TooGenericExceptionCaught")

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
                pdf.addText("Type: " + metadata.type)
                metadata.filer.forEach { fil ->
                    pdf.addText("Filnavn: " + fil.filnavn)
                }

                pdf.finish()
            }
        } catch (e: Exception) {
            throw PdfGenerationException("Error while creating pdf", e)
        }
}

fun formatLocalDateTime(dato: LocalDateTime): String {
    val datoFormatter = DateTimeFormatter.ofPattern("d. MMMM yyyy 'kl.' HH.mm", Locale.forLanguageTag("nb"))
    return dato.format(datoFormatter)
}

class PdfGenerationException(message: String, cause: Throwable) : RuntimeException(message, cause)
