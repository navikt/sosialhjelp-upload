package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import org.jooq.Configuration
import org.jooq.DSLContext
import java.util.*

class DocumentRepository(
    private val dsl: DSLContext,
) {
    class DocumentOwnedByAnotherUserException : RuntimeException()

    fun getDocument(
        tx: Configuration,
        id: UUID,
    ) = tx
        .dsl()
        .selectFrom(DOCUMENT)
        .where(DOCUMENT.ID.eq(id))
        .fetchSingle()

    fun getOrCreateDocument(
        tx: Configuration,
        externalId: String,
        personIdent: String,
    ): UUID {
        if (isOwnedByAnotherUser(externalId, personIdent)) {
            throw DocumentOwnedByAnotherUserException()
        }

        val insertedId =
            tx
                .dsl()
                .insertInto(DOCUMENT)
                .set(DOCUMENT.ID, UUID.randomUUID())
                .set(DOCUMENT.OWNER_IDENT, personIdent)
                .set(DOCUMENT.EXTERNAL_ID, externalId)
                .onDuplicateKeyIgnore()
                .returning(DOCUMENT.ID)
                .fetchOne()
                ?.get(DOCUMENT.ID)

        if (insertedId != null) return insertedId

        // Otherwise, select existing
        return tx
            .dsl()
            .select(DOCUMENT.ID)
            .from(DOCUMENT)
            .where(
                DOCUMENT.EXTERNAL_ID
                    .eq(externalId)
                    .and(DOCUMENT.OWNER_IDENT.eq(personIdent)),
            ).fetchOne()
            ?.get(DOCUMENT.ID) ?: error("Could not find or create document")
    }

    private fun isOwnedByAnotherUser(
        externalId: String,
        personIdent: String,
    ): Boolean {
        val count =
            dsl
                .selectCount()
                .from(DOCUMENT)
                .where(
                    DOCUMENT.EXTERNAL_ID
                        .eq(externalId)
                        .and(DOCUMENT.OWNER_IDENT.ne(personIdent)),
                ).fetchSingle()
                .value1()
        return count > 0
    }

    fun isOwnedByUser(
        id: UUID,
        personIdent: String,
    ): Boolean {
        val count =
            dsl
                .selectCount()
                .from(DOCUMENT)
                .where(
                    DOCUMENT.ID
                        .eq(id)
                        .and(DOCUMENT.OWNER_IDENT.eq(personIdent)),
                ).fetchOne()
                ?.value1()
        return count != 0
    }

    fun cleanup(
        tx: Configuration,
        documentId: UUID,
    ): Int =
        tx
            .dsl()
            .deleteFrom(DOCUMENT)
            .where(DOCUMENT.ID.eq(documentId))
            .execute()
}
