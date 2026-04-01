package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.*

class SubmissionRepository(
    private val dsl: DSLContext,
) {
    class SubmissionOwnedByAnotherUserException : RuntimeException()

    data class StaleSubmission(val id: UUID, val navEksternRefId: String)

    fun getSubmission(tx: Configuration, contextId: String, personIdent: String): UUID {
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

    fun getNavEksternRefId(tx: Configuration, submissionId: UUID, personIdent: String): String {
        return tx
            .dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.ID
                    .eq(submissionId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchOne()
            ?.get(SUBMISSION.NAV_EKSTERN_REF_ID) ?: error("Could not find or create submission")
    }

    fun getOrCreateSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        navEksternRefId: String,
    ): UUID {
        if (isOwnedByAnotherUser(tx, contextId, personIdent)) {
            throw SubmissionOwnedByAnotherUserException()
        }

        val insertedId =
            tx
                .dsl()
                .insertInto(SUBMISSION)
                .set(SUBMISSION.ID, UUID.randomUUID())
                .set(SUBMISSION.OWNER_IDENT, personIdent)
                .set(SUBMISSION.CONTEXT_ID, contextId)
                .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
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
        tx: Configuration,
        contextId: String,
        personIdent: String,
    ): Boolean {
        val count =
            tx
                .dsl()
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

    fun getStaleSubmissions(
        tx: Configuration,
        cutoff: OffsetDateTime,
    ): List<StaleSubmission> =
        tx
            .dsl()
            .select(SUBMISSION.ID, SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .join(UPLOAD).on(UPLOAD.SUBMISSION_ID.eq(SUBMISSION.ID))
            .groupBy(SUBMISSION.ID, SUBMISSION.NAV_EKSTERN_REF_ID)
            .having(
                DSL.max(UPLOAD.UPDATED_AT).lt(cutoff)
                    .and(
                        DSL.count().filterWhere(
                            UPLOAD.PROCESSING_STATUS.`in`("PENDING", "PROCESSING"),
                        ).eq(0),
                    ),
            )
            .fetch()
            .map { StaleSubmission(it[SUBMISSION.ID]!!, it[SUBMISSION.NAV_EKSTERN_REF_ID]!!) }
}

