
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.FileFactory
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.*

fun Route.configureActionRoutes() {
    val gotenbergService = GotenbergService(environment)
    val uploadRepository = UploadRepository()
    val documentRepository = DocumentRepository()
    val fileFactory = FileFactory(environment)

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    get("/document/{soknadId}/{vedleggType}/submit") {
        val document = transaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters)) }
        val files = transaction { uploadRepository.getUploadsByDocumentId(document) }.map { fileFactory.uploadPdfFile(it.value) }
        call.respondBytes(gotenbergService.merge(files), ContentType.Application.Pdf)
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}
