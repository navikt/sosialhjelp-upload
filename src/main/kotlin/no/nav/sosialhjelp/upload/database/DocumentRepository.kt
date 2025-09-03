package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import no.nav.sosialhjelp.upload.common.SoknadDocumentIdent
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENTS
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.*

class DocumentRepository(private val dsl: DSLContext) {
    class DocumentOwnedByAnotherUserException : RuntimeException()

    suspend fun getDocument(tx: Configuration, id: UUID) =
        tx.dsl().selectFrom(DOCUMENTS).where(DOCUMENTS.ID.eq(id)).awaitSingle()

    suspend fun getOrCreateDocument(
        tx: Configuration,
        externalId: String,
        personIdent: String,
    ): UUID {
        if (isOwnedByUser(personIdent)) {
            throw DocumentOwnedByAnotherUserException()
        }

        val insertedId = tx.dsl().insertInto(DOCUMENTS).set(DOCUMENTS.OWNER_IDENT, personIdent).onDuplicateKeyIgnore().returning(DOCUMENTS.ID).awaitFirstOrNull()?.get(DOCUMENTS.ID)
        if (insertedId != null) return insertedId
        // Otherwise, select existing
        return tx.dsl().select(DOCUMENTS.ID)
            .from(DOCUMENTS)
            .where(
                DOCUMENTS.SOKNAD_ID.eq(soknadDocumentIdent.soknadId)
                    .and(
                        DOCUMENTS.VEDLEGG_TYPE.eq(soknadDocumentIdent.vedleggType)
                    )
                    .and(DOCUMENTS.OWNER_IDENT.eq(personIdent))
            )
            .awaitSingle()
            .get(DOCUMENTS.ID) ?: error("Could not find or create document")

    }

    private suspend fun isOwnedByUser(
        externalId: String,
        personIdent: String,
    ): Boolean {
        val count = dsl.selectCount()
            .from(DOCUMENTS)
            .where(
                // TODO: External id ja
                DOCUMENTS.ID.eq(soknadDocumentIdent.soknadId)
                    .and(DSL.field("vedlegg_type").eq(soknadDocumentIdent.vedleggType))
                    .and(DSL.field("owner_ident").ne(personIdent))
            )
            .awaitSingle()?.value1()
        return count != 0
    }

    suspend fun isOwnedByUser(
        id: UUID,
        personIdent: String,
    ): Boolean {
        val count = dsl.selectCount()
            .from(DOCUMENTS)
            .where(
                DOCUMENTS.ID.eq(id)
                    .and(DOCUMENTS.OWNER_IDENT.eq(personIdent))
            )
            .awaitSingle()?.value1()
        return count != 0
    }
}
