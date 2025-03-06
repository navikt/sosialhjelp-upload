package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

object MainPdfTable : UUIDTable() {
    val uploadId = reference("UPLOAD_ID", UploadTable, onDelete = ReferenceOption.CASCADE)
}
