package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.ReferenceOption

object PageTable : UUIDTable() {
    val upload = reference("upload", UploadTable, onDelete = ReferenceOption.CASCADE)
    val pageNumber = integer("page_number")
    val filename = varchar("filename", 100).nullable()

    init {
        uniqueIndex(upload, pageNumber)
    }
}
