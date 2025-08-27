package no.nav.sosialhjelp.database.schema

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.UUIDTable


object PageTable : UUIDTable("pages") {
    val upload = reference("upload", UploadTable, onDelete = ReferenceOption.CASCADE)
    val pageNumber = integer("page_number")
    val filename = varchar("filename", 100).nullable()

    init {
        uniqueIndex(upload, pageNumber)
    }
}
