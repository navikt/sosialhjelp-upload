
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.DocumentTable.ownerIdent
import no.nav.sosialhjelp.database.schema.DocumentTable.soknadId
import no.nav.sosialhjelp.database.schema.DocumentTable.vedleggType
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnoreAndGetId
import org.jetbrains.exposed.sql.not
import java.util.*

class DocumentRepository {
    class DocumentOwnedByAnotherUserException : RuntimeException()

    fun getOrCreateDocument(
        documentIdent: DocumentIdent,
        personIdent: String,
    ): EntityID<UUID> =
        if (isNotOwnedByUser(documentIdent, personIdent)) {
            throw DocumentOwnedByAnotherUserException()
        } else {
            DocumentTable
                .insertIgnoreAndGetId {
                    it[soknadId] = documentIdent.soknadId
                    it[vedleggType] = documentIdent.vedleggType
                    it[ownerIdent] = personIdent
                } ?: DocumentTable
                .select(DocumentTable.id)
                .where(documentMatch(documentIdent) and ownerMatch(personIdent))
                .single()
                .let { it[DocumentTable.id] }
        }

    private fun isNotOwnedByUser(
        documentIdent: DocumentIdent,
        personIdent: String,
    ): Boolean =
        DocumentTable
            .select(DocumentTable.id)
            .where(documentMatch(documentIdent) and not(ownerMatch(personIdent)))
            .count() != 0L

    companion object {
        fun documentMatch(documentIdent: DocumentIdent) =
            (soknadId eq documentIdent.soknadId) and (vedleggType eq documentIdent.vedleggType)

        fun ownerMatch(personIdent: String) = ownerIdent eq personIdent
    }
}
