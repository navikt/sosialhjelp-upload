package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object UploadTable : UUIDTable() {
    val originalFilename = varchar("original_filename", 255)
    val numPages = integer("num_pages").nullable()
    val sortingIndex = integer("sorting_index").autoIncrement()
    val document = reference("document_id", DocumentTable)
}
