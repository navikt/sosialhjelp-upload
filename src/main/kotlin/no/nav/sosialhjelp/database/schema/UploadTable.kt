package no.nav.sosialhjelp.database.schema

import org.jetbrains.exposed.v1.core.dao.id.UUIDTable


object UploadTable : UUIDTable("uploads") {
    val originalFilename = varchar("original_filename", 255)
    val document = reference("document_id", DocumentTable)
}
