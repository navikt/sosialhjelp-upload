package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.DocumentRepository.DocumentOwnedByAnotherUserException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.pdf.GotenbergService
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.io.File
import java.util.*

fun Route.configureActionRoutes() {
    val gotenbergService: GotenbergService by application.dependencies
    val uploadRepository: UploadRepository by application.dependencies
    val documentRepository: DocumentRepository by application.dependencies
    val filePathFactory: FilePathFactory by application.dependencies
    val downstreamUploadService: DownstreamUploadService by application.dependencies
    val dsl: DSLContext by application.dependencies

    post("/document/{documentId}/submit") {
        val personIdent = call.principal<JWTPrincipal>()?.subject ?: error("personident is required")
        val documentId = call.parameters["documentId"]?.let { UUID.fromString(it) } ?: error("documentId is required")
        val input = call.receive<SubmitInput>()
        if (!documentRepository.isOwnedByUser(documentId, personIdent)) {
            call.respondText("Access denied", status = HttpStatusCode.Forbidden)
            return@post
        }

        val uploads = dsl.transactionCoroutine(Dispatchers.IO) { uploadRepository.getUploadsByDocumentId(it, documentId) }
        val files = uploads.map { File(filePathFactory.getConvertedPdfPath(it).toString()) }
        val response = downstreamUploadService.upload(input.targetUrl, gotenbergService.merge(files), "$documentId.pdf")
        val body = response.bodyAsText()
        println(body)
    }

    delete("/document/{soknadId}/{vedleggType}") {
    }
}

@Serializable
data class SubmitInput(val targetUrl: String)
