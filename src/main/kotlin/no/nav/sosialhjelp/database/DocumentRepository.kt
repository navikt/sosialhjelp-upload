
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.DocumentTable.ownerIdent
import no.nav.sosialhjelp.database.schema.DocumentTable.soknadId
import no.nav.sosialhjelp.database.schema.DocumentTable.vedleggType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import java.util.*

class DocumentRepository {
    fun getOrCreateDocument(
        documentIdent: DocumentIdent,
        personIdent: String,
    ): EntityID<UUID> =
        DocumentTable
            .insertIgnoreAndGetId {
                it[soknadId] = documentIdent.soknadId
                it[vedleggType] = documentIdent.vedleggType
                it[ownerIdent] = personIdent
            } ?: DocumentTable
            .select(DocumentTable.id)
            .where {
                (
                    soknadId eq documentIdent.soknadId
                ) and (
                    vedleggType eq documentIdent.vedleggType
                ) and (
                    ownerIdent eq personIdent
                )
            }.map { it[DocumentTable.id] }
            .first()
}
