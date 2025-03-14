package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.client.statement.*
import io.ktor.server.application.*
import no.nav.sosialhjelp.PdfThumbnailService
import no.nav.sosialhjelp.schema.DocumentEntity
import no.nav.sosialhjelp.schema.DocumentTable
import no.nav.sosialhjelp.schema.UploadEntity
import no.nav.sosialhjelp.schema.UploadTable
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*

class TusService(
    val environment: ApplicationEnvironment,
) {
    val pdfConversionService = PdfConversionService()
    val pdfThumbnailService = PdfThumbnailService(environment)

    fun getOrCreateDocument(
        soknadId: UUID,
        vedleggType: String,
    ): DocumentEntity =
        transaction {
            val document =
                DocumentEntity
                    .find { (DocumentTable.soknadId eq soknadId) and (DocumentTable.vedleggType eq vedleggType) }
                    .firstOrNull()
            return@transaction if (document != null) {
                environment.log.info("document entity exists")
                document
            } else {
                DocumentEntity
                    .new {
                        this.soknadId = soknadId
                        this.vedleggType = vedleggType
                    }.also {
                        environment.log.info("created new document entity, ID: ${it.id}")
                    }
            }
        }

    fun preCreate(request: HookRequest): HookResponse {
        require(request.Type == HookType.PreCreate)
        println("request: $request")
        val originalFilename = request.Event.Upload.MetaData.filename

        val document =
            getOrCreateDocument(
                soknadId = UUID.fromString(request.Event.Upload.MetaData.soknadId),
                vedleggType = request.Event.Upload.MetaData.vedleggType,
            )

        val uploadId =
            transaction {
                UploadTable
                    .insertAndGetId {
                        it[UploadTable.originalFilename] = originalFilename
                        it[UploadTable.document] = document.id
                    }.value
            }

        environment.log.info("Creating a new upload, ID: $uploadId for document ${document.id}")
        return HookResponse(changeFileInfo = FileInfoChanges(id = uploadId.toString()))
    }

    fun postCreate(request: HookRequest) {
        require(request.Type == HookType.PostCreate)
    }

    suspend fun postFinish(request: HookRequest) {
        require(request.Type == HookType.PostFinish)

        val upload = transaction { UploadEntity.find { UploadTable.id eq UUID.fromString(request.Event.Upload.ID) }.first() }

        val originalFileExtension = File(upload.originalFilename).extension

        val outputFile = File("./tusd-data/${upload.id}.pdf")

        outputFile.writeBytes(
            pdfConversionService
                .convertToPdf(FinishedUpload(File("./tusd-data/${upload.id}"), originalFileExtension))
                .readRawBytes(),
        )

        pdfThumbnailService.makeThumbnails(upload, outputFile)
    }
}
