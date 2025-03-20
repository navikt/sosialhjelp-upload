package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.client.statement.*
import io.ktor.server.application.*
import no.nav.sosialhjelp.PdfThumbnailService
import no.nav.sosialhjelp.progress.DocumentIdent
import no.nav.sosialhjelp.schema.DocumentTable.getOrCreateDocument
import no.nav.sosialhjelp.schema.UploadTable
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

class TusService(
    val environment: ApplicationEnvironment,
) {
    val gotenbergService = GotenbergService(environment)
    val pdfThumbnailService = PdfThumbnailService(environment)

    fun getIdent(request: HookRequest): DocumentIdent =
        DocumentIdent(
            soknadId = UUID.fromString(request.Event.Upload.MetaData.soknadId),
            vedleggType = request.Event.Upload.MetaData.vedleggType,
        )

    fun preCreate(request: HookRequest): HookResponse {
        require(request.Type == HookType.PreCreate)
        println("request: $request")
        val originalFilename = request.Event.Upload.MetaData.filename

        val uploadId =
            transaction {
                UploadTable
                    .insertAndGetId {
                        it[UploadTable.originalFilename] = originalFilename
                        it[document] = getOrCreateDocument(getIdent(request))
                    }.value
            }

        transaction {
            exec("NOTIFY \"upload::$uploadId\"")
        }

        environment.log.info("Creating a new upload, ID: $uploadId for document ${getDocumentIdFromUploadId(uploadId)}")
        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    fun postCreate(request: HookRequest) {
        require(request.Type == HookType.PostCreate)
    }

    fun getDocumentIdFromUploadId(uploadId: UUID): EntityID<UUID> =
        transaction {
            UploadTable
                .select(UploadTable.document)
                .where { UploadTable.id eq uploadId }
                .map { it[UploadTable.document] }
                .first()
        }

    suspend fun postFinish(request: HookRequest) {
        require(request.Type == HookType.PostFinish)

        val uploadId = UUID.fromString(request.Event.Upload.ID)

        val originalFilename =
            transaction {
                UploadTable
                    .select(UploadTable.originalFilename)
                    .where { UploadTable.id eq uploadId }
                    .map { it[UploadTable.originalFilename] }
                    .first()
            }

        val originalFileExtension = File(originalFilename).extension

        val outputFile = File("./tusd-data/$uploadId.pdf")

        outputFile.writeBytes(
            gotenbergService
                .convertToPdf(FinishedUpload(File("./tusd-data/$uploadId"), originalFileExtension))
                .readRawBytes(),
        )

        pdfThumbnailService.makeThumbnails(uploadId, outputFile)
        transaction {
            exec("NOTIFY \"upload::$uploadId\"")
            exec("NOTIFY \"document::${getDocumentIdFromUploadId(uploadId)}\"")
        }
    }
}
