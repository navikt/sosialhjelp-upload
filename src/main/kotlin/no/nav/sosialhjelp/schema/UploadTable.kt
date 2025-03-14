package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

object UploadTable : UUIDTable() {
    val originalFilename = varchar("original_filename", 255)
    val numPages = integer("num_pages").nullable()
    val documentId = reference("document_id", DocumentTable, onDelete = ReferenceOption.CASCADE)
}
