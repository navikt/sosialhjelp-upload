package no.nav.sosialhjelp.action

import DocumentRepository
import DocumentRepository.DocumentOwnedByAnotherUserException
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.io.files.Path
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.FilePathFactory
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.io.File
import java.util.*

fun Route.configureActionRoutes() {
    val gotenbergService = GotenbergService(environment)
    val uploadRepository = UploadRepository()
    val documentRepository = DocumentRepository()
    val filePathFactory = FilePathFactory(Path(environment.config.property("storage.basePath").getString()))
    val downstreamUploadService = DownstreamUploadService()

    fun fromParameters(parameters: Parameters) =
        DocumentIdent(
            soknadId = UUID.fromString(parameters["soknadId"] ?: error("uploadId is required")),
            vedleggType = parameters["vedleggType"] ?: error("vedleggType is required"),
        )

    post("/document/{soknadId}/{vedleggType}/submit") {
        val personIdent = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val requestBody = call.receive<DocumentSubmitInput>()
        val documentId =
            try {
                newSuspendedTransaction { documentRepository.getOrCreateDocument(fromParameters(call.parameters), personIdent) }
            } catch (_: DocumentOwnedByAnotherUserException) {
                call.respondText("Access denied", status = HttpStatusCode.Forbidden)
                return@post
            }

        val uploads = newSuspendedTransaction { uploadRepository.getUploadsByDocumentId(documentId) }
        val files = uploads.map { File(filePathFactory.getConvertedPdfPath(it.value).name) }
        downstreamUploadService.upload(requestBody.targetUrl, if (files.size > 1) gotenbergService.merge(files) else files.first().readBytes(), "$documentId.pdf")
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}
