package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object UploadTable : UUIDTable() {
    val originalFilename = varchar("original_filename", 255)
    val document = reference("document_id", DocumentTable)
}
