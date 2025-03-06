package no.nav.sosialhjelp.schema

import org.jetbrains.exposed.dao.id.UUIDTable

object UploadTable : UUIDTable() {
    val originalFilename = varchar("originalFilename", 255)
}
