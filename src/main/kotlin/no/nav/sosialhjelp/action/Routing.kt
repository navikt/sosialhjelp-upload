
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.UploadTable
import no.nav.sosialhjelp.pdf.GotenbergService
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

fun Route.configureActionRoutes() {
    val gotenbergService = GotenbergService(environment)

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )
    get("/document/{soknadId}/{vedleggType}/submit") {
        val document = transaction { DocumentTable.getOrCreateDocument(fromParameters(call.parameters)) }

        val pdfFiles =
            transaction {
                UploadTable
                    .select(UploadTable.id)
                    .where { UploadTable.document eq document }
                    .map { it[UploadTable.id] }
            }

        println(pdfFiles)

        val files = pdfFiles.map { File("./tusd-data/$it.pdf") }

        call.respondBytes(gotenbergService.merge(files), ContentType.Application.Pdf)
    }

    delete("/document/{documentId}") {
    }
}
