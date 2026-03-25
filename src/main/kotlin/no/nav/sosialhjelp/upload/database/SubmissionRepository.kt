package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import org.jooq.Configuration
import org.jooq.DSLContext
import java.util.*

class SubmissionRepository(
    private val dsl: DSLContext,
) {
    class SubmissionOwnedByAnotherUserException : RuntimeException()

    fun getSubmission(
        tx: Configuration,
        id: UUID,
    ) = tx
        .dsl()
        .selectFrom(SUBMISSION)
        .where(SUBMISSION.ID.eq(id))
        .fetchSingle()

    fun getOrCreateSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        navEksternRefId: String? = null,
    ): UUID {
        if (isOwnedByAnotherUser(contextId, personIdent)) {
            throw SubmissionOwnedByAnotherUserException()
        }

        val insertedId =
            tx
                .dsl()
                .insertInto(SUBMISSION)
                .set(SUBMISSION.ID, UUID.randomUUID())
                .set(SUBMISSION.OWNER_IDENT, personIdent)
                .set(SUBMISSION.CONTEXT_ID, contextId)
                .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId ?: UUID.randomUUID().toString())
                .onDuplicateKeyIgnore()
                .returning(SUBMISSION.ID)
                .fetchOne()
                ?.get(SUBMISSION.ID)

        if (insertedId != null) return insertedId

        return tx
            .dsl()
            .select(SUBMISSION.ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.CONTEXT_ID
                    .eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchOne()
            ?.get(SUBMISSION.ID) ?: error("Could not find or create submission")
    }

    private fun isOwnedByAnotherUser(
        contextId: String,
        personIdent: String,
    ): Boolean {
        val count =
            dsl
                .selectCount()
                .from(SUBMISSION)
                .where(
                    SUBMISSION.CONTEXT_ID
                        .eq(contextId)
                        .and(SUBMISSION.OWNER_IDENT.ne(personIdent)),
                ).fetchSingle()
                .value1()
        return count > 0
    }

    fun isOwnedByUser(
        tx: Configuration,
        id: UUID,
        personIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.ID
                    .eq(id)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchSingle()
            .value1() > 0

    fun isOwnedByUser(
        id: UUID,
        personIdent: String,
    ): Boolean {
        val count =
            dsl
                .selectCount()
                .from(SUBMISSION)
                .where(
                    SUBMISSION.ID
                        .eq(id)
                        .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
                ).fetchOne()
                ?.value1()
        return count != 0
    }

    fun cleanup(
        tx: Configuration,
        submissionId: UUID,
    ): Int =
        tx
            .dsl()
            .deleteFrom(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .execute()
}

