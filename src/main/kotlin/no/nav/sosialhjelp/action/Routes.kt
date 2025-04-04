
import DocumentRepository.DocumentOwnedByAnotherUserException
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import no.nav.sosialhjelp.action.DocumentSubmitInput
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.FileFactory
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
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

    post("/document/{soknadId}/{vedleggType}/submit") {
        val personident = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val requestBody = call.receive<DocumentSubmitInput>()
        val documentId =
            try {
                newSuspendedTransaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters), personident) }
            } catch (_: DocumentOwnedByAnotherUserException) {
                call.respondText("Access denied", status = HttpStatusCode.Forbidden)
                return@post
            }

        val uploads = newSuspendedTransaction { uploadRepository.getUploadsByDocumentId(documentId) }
        val files = uploads.map { fileFactory.uploadConvertedPdf(it.value) }
        uploadClient.upload(requestBody.targetUrl, gotenbergService.merge(files), "$documentId.pdf")
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}
