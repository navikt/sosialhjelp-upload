package no.nav.sosialhjelp.tusd

import HookType
import io.ktor.client.statement.*
import io.ktor.server.application.*
import no.nav.sosialhjelp.lol.UploadTable
import no.nav.sosialhjelp.tusd.dto.FileInfoChanges
import no.nav.sosialhjelp.tusd.dto.HookRequest
import no.nav.sosialhjelp.tusd.dto.HookResponse
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import java.util.*
import kotlin.io.path.Path

class TusService(
    val environment: ApplicationEnvironment,
) {
    val pdfConversionService = PdfConversionService()

    fun preCreate(request: HookRequest): HookResponse {
        require(request.Type == HookType.PreCreate)
        println("request: $request")

        environment.log.info("Creating a new upload, id: ${request.Event.Upload.ID}")
        val foo =
            transaction {
                UploadTable.insertAndGetId {
                    it[originalFilename] = request.Event.Upload.MetaData.filename
                }
            }
        return HookResponse(changeFileInfo = FileInfoChanges(id = foo.toString()))
    }

    fun postCreate(request: HookRequest) {
        require(request.Type == HookType.PostCreate)
    }

    suspend fun postFinish(request: HookRequest) {
        require(request.Type == HookType.PostFinish)

        val (uploadId, originalFilename) =
            transaction {
                UploadTable
                    .select(UploadTable.id, UploadTable.originalFilename)
                    .where { UploadTable.id eq UUID.fromString(request.Event.Upload.ID) }
                    .single()
            }.let { it[UploadTable.id] to it[UploadTable.originalFilename] }

        File(
            "./tus-data/$uploadId.pdf",
        ).writeBytes(pdfConversionService.convertToPdf(FinishedUpload(File("./tus-data/$uploadId"), originalFilename)).readRawBytes())
    }
}

fun lol(filename: String) {
    val basePath = Path("/tusd-data")
    val localBase = Path("./tusd-data")
    val uploadFilename = localBase.resolve(basePath.relativize(Path(filename))).toFile()
    require(uploadFilename.exists() && uploadFilename.isFile()) {
        "Upload file does not exist or is not a file: $uploadFilename"
    }
}
