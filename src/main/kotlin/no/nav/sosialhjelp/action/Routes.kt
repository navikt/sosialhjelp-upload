package no.nav.sosialhjelp.action

import DocumentRepository
import DocumentRepository.DocumentOwnedByAnotherUserException
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.common.FilePathFactory
import no.nav.sosialhjelp.database.UploadRepository
import no.nav.sosialhjelp.pdf.GotenbergService
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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
        val documentIdent = fromParameters(call.parameters)
        val (soknadId, vedleggType) = documentIdent
        val documentId =
            try {
                suspendTransaction(Dispatchers.IO) { documentRepository.getOrCreateDocument(documentIdent, personIdent) }
            } catch (_: DocumentOwnedByAnotherUserException) {
                call.respondText("Access denied", status = HttpStatusCode.Forbidden)
                return@post
            }

        val uploads = suspendTransaction(Dispatchers.IO) { uploadRepository.getUploadsByDocumentId(documentId) }
        val files = uploads.map { File(filePathFactory.getConvertedPdfPath(it.value).toString()) }
        val response = downstreamUploadService.upload(Url("http://localhost:8181/sosialhjelp/soknad-api/dokument/${soknadId}/${vedleggType}"), gotenbergService.merge(files), "$documentId.pdf")
        println(response.status)
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}
