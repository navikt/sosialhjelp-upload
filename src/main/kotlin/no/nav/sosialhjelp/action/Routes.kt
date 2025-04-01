
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.action.DocumentSubmitInput
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
    val uploadClient =
        HttpClient(CIO) {
            expectSuccess = false
        }

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    suspend fun HttpClient.upload(
        url: Url,
        bytes: ByteArray,
        filename: String,
    ) {
        this.request {
            url(url)
            method = HttpMethod.Post
            formData {
                append(
                    "file",
                    bytes,
                    Headers.build {
                        append(HttpHeaders.ContentType, ContentType.Application.Pdf.toString())
                        append(HttpHeaders.ContentDisposition, "attachment; filename=$filename")
                    },
                )
            }
        }
    }

    get("/document/{soknadId}/{vedleggType}/submit") {
        val personident = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val requestBody = call.receive<DocumentSubmitInput>()
        val documentId = transaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters), personident) }
        val files = transaction { uploadRepository.getUploadsByDocumentId(documentId) }.map { fileFactory.uploadPdfFile(it.value) }
        uploadClient.upload(requestBody.targetUrl, gotenbergService.merge(files), "$documentId.pdf")
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}
