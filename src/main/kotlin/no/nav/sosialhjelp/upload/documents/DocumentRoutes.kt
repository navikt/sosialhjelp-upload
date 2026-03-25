package no.nav.sosialhjelp.upload.documents

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFile
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.get
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.database.UploadRepository
import org.jooq.DSLContext
import java.io.File
import java.util.UUID

fun Route.configureDocumentRoutes() {
    val mellomlagringClient: MellomlagringClient by application.dependencies
    val uploadRepository: UploadRepository by application.dependencies
    val dsl: DSLContext by application.dependencies

    get("/upload/{uploadId}") {
        val id = call.parameters["uploadId"] ?: error("uploadId is required")
        call.principal<JWTPrincipal>()?.subject
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Missing subject in token")
        val token = call.request.header("Authorization")?.removePrefix("Bearer ")
            ?: return@get call.respond(HttpStatusCode.Unauthorized, "Missing bearer token")

        val upload = dsl.transactionResult { tx ->
            uploadRepository.getUpload(tx, UUID.fromString(id))
        }
        checkNotNull(upload.navEksternRefId) { "Mangler navEksternRefId. Er ikke fil ferdig opplastet?" }
        checkNotNull(upload.filId) { "Mangler filId. Er ikke fil ferdig opplastet?" }
        checkNotNull(upload.mellomlagringFilnavn) { "Mangler originalFilename. Er ikke fil ferdig opplastet?" }


        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${upload.mellomlagringFilnavn}\"")
        call.respondBytes(mellomlagringClient.getFile(upload.navEksternRefId!!, upload.filId!!, token), ContentType.defaultForFile(File(upload.mellomlagringFilnavn)))
    }
}
