import kotlinx.coroutines.flow.single
import no.nav.sosialhjelp.common.DocumentIdent
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.DocumentTable.ownerIdent
import no.nav.sosialhjelp.database.schema.DocumentTable.soknadId
import no.nav.sosialhjelp.database.schema.DocumentTable.vedleggType
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.r2dbc.insertIgnoreAndGetId
import org.jetbrains.exposed.v1.r2dbc.select
import java.util.*

class DocumentRepository {
    class DocumentOwnedByAnotherUserException : RuntimeException()

    suspend fun getOrCreateDocument(
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

    private suspend fun isNotOwnedByUser(
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
