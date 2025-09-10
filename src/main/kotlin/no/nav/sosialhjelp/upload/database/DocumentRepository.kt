package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import org.jooq.Configuration
import org.jooq.DSLContext
import java.util.*

class DocumentRepository(private val dsl: DSLContext) {
    class DocumentOwnedByAnotherUserException : RuntimeException()

    suspend fun getDocument(tx: Configuration, id: UUID) =
        tx.dsl().selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(id)).awaitSingle()

    suspend fun getOrCreateDocument(
        tx: Configuration,
        externalId: String,
        personIdent: String,
    ): UUID {
        if (isOwnedByUser(externalId, personIdent)) {
            throw DocumentOwnedByAnotherUserException()
        }

        val insertedId = tx.dsl()
            .insertInto(DOCUMENT)
            .set(DOCUMENT.ID, UUID.randomUUID())
            .set(DOCUMENT.OWNER_IDENT, personIdent)
            .set(DOCUMENT.EXTERNAL_ID, externalId)
            .onDuplicateKeyIgnore()
            .returning(DOCUMENT.ID)
            .awaitFirstOrNull()?.get(DOCUMENT.ID)

        if (insertedId != null) return insertedId

        // Otherwise, select existing
        return tx.dsl().select(DOCUMENT.ID)
            .from(DOCUMENT)
            .where(
                DOCUMENT.EXTERNAL_ID.eq(externalId)
                    .and(DOCUMENT.OWNER_IDENT.eq(personIdent))
            )
            .awaitSingle()
            .get(DOCUMENT.ID) ?: error("Could not find or create document")

    }

    private suspend fun isOwnedByUser(
        externalId: String,
        personIdent: String,
    ): Boolean {
        val count = dsl.selectCount()
            .from(DOCUMENT)
            .where(
                // TODO: External id ja
                DOCUMENT.ID.eq(UUID.randomUUID())
                    .and(DOCUMENT.EXTERNAL_ID.eq(externalId))
                    .and(DOCUMENT.OWNER_IDENT.eq(personIdent))
            )
            .awaitSingle()?.value1()
        return count != 0
    }

    suspend fun isOwnedByUser(
        id: UUID,
        personIdent: String,
    ): Boolean {
        val count = dsl.selectCount()
            .from(DOCUMENT)
            .where(
                DOCUMENT.ID.eq(id)
                    .and(DOCUMENT.OWNER_IDENT.eq(personIdent))
            )
            .awaitSingle()?.value1()
        return count != 0
    }

    suspend fun cleanup(tx: Configuration, documentId: UUID): Int {
        return tx.dsl().deleteFrom(DOCUMENT).where(DOCUMENT.ID.eq(documentId)).awaitSingle()
    }
}
