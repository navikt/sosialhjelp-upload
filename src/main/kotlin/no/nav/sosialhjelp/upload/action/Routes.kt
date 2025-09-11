package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.database.DocumentRepository
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import no.nav.sosialhjelp.upload.common.FilePathFactory
import no.nav.sosialhjelp.upload.database.DocumentChangeNotifier
import no.nav.sosialhjelp.upload.database.UploadRepository
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import java.io.File
import java.util.*

@Serializable
data class Metadata(
    val type: String,
    val tilleggsinfo: String? = null,
    val innsendelsesfrist: String? = null,
    val hendelsetype: String? = null,
    val hendelsereferanse: String? = null,
)

@Serializable
data class SubmitInput(
    val fiksDigisosId: String? = null,
    val metadata: Metadata,
    val mellomlagring: Boolean,
)

fun Route.configureActionRoutes() {
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
            return@post call.respondText("Access denied", status = HttpStatusCode.Forbidden)
        }

        val uploads = dsl.transactionCoroutine(Dispatchers.IO) { tx ->
            uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
        }

        val response = downstreamUploadService.upload(
            input.metadata,
            fiksDigisosId = input.fiksDigisosId!!,
            files = uploads.map { upload ->
                val file =
                    File(upload.convertedFilename?.let { filePathFactory.getConvertedPdfPath(upload.id!!).toString() } ?: filePathFactory.getOriginalCopyPoth(upload.originalFilename!!).toString())
                Upload(file.readBytes(), upload.originalFilename!!, file.extension)
            },
            call.request.header("Authorization")!!.removePrefix("Bearer "),
        )
        if (response.status.isSuccess()) {
            dsl.transactionCoroutine(Dispatchers.IO) { tx ->
                documentRepository.cleanup(tx, documentId)
                DocumentChangeNotifier.notifyChange(documentId)
            }
            call.respond(HttpStatusCode.Created)
        }
    }
}

//@Serializable
//data class SubmitInput(val targetUrl: String)
