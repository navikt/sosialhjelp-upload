package no.nav.sosialhjelp.lol

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

enum class UploadState {
    UPLOADING,
    UPLOAD_COMPLETE,
    CONVERTING,
    CONVERT_COMPLETE,
    THUMBNAILING,
    SUCCESS,
    FAILED,
}

object UploadTable : UUIDTable() {
    val originalFilename = varchar("originalFilename", 255)
}

object ThumbnailTable : UUIDTable() {
    val uploadId = reference("UPLOAD_ID", UploadTable, onDelete = ReferenceOption.CASCADE)
}

object MainPdfTable : UUIDTable() {
    val uploadId = reference("UPLOAD_ID", UploadTable, onDelete = ReferenceOption.CASCADE)
}
