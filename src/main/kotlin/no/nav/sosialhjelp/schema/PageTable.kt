package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.CompositeIdTable
import org.jetbrains.exposed.sql.ReferenceOption

object PageTable : CompositeIdTable() {
    val uploadId = reference("upload_id", UploadTable, onDelete = ReferenceOption.CASCADE).entityId()
    val pageNumber = integer("page_number").entityId()
    val filename = varchar("filename", 100).nullable()

    override val primaryKey = PrimaryKey(uploadId, pageNumber)
}
