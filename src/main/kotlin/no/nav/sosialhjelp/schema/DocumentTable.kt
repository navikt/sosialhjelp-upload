package no.nav.sosialhjelp.schema

import no.nav.sosialhjelp.schema.UploadTable.documentId
import org.jetbrains.exposed.dao.id.CompositeIdTable

object DocumentTable : CompositeIdTable() {
    val vedleggType = varchar("vedleggType", 255).entityId()
    val soknadId = uuid("soknadId").entityId()

    override val primaryKey = PrimaryKey(documentId, vedleggType)
}
