package no.nav.sosialhjelp.database.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object UploadTable : UUIDTable() {
    val originalFilename = varchar("original_filename", 255)
    val originalExtension = varchar("original_extension", 10)
    val document = reference("document_id", DocumentTable)
}
