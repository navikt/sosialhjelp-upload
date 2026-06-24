package no.nav.sosialhjelp.upload.tus

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import org.jooq.Configuration
import org.jooq.impl.DSL.param
import java.util.UUID

/**
 * DB queries for the TUS upload creation flow:
 * finding or creating submissions, managing navEksternRefId, and advisory locking.
 */
class TusSubmissionQueries {
    class SubmissionOwnedByAnotherUserException : RuntimeException()

    /**
     * Finds an existing submission for [contextId] owned by [personIdent].
     * Throws [SubmissionOwnedByAnotherUserException] if the contextId is owned by someone else.
     * Returns null if no submission exists yet.
     */
    fun findSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
    ): UUID? {
        if (isOwnedByAnotherUser(tx, contextId, personIdent)) {
            throw SubmissionOwnedByAnotherUserException()
        }
        return tx
            .dsl()
            .select(SUBMISSION.ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.CONTEXT_ID.eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            )
            .fetchOne()
            ?.get(SUBMISSION.ID)
    }

    /**
     * Gets an existing submission for [contextId] + [personIdent], or creates one if none exists.
     * Updates [fiksDigisosId] and [kategori] on the existing row if they were null.
     * Throws [SubmissionOwnedByAnotherUserException] if the contextId is owned by someone else.
     */
    fun getOrCreateSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        fiksDigisosId: String? = null,
        kategori: String? = null,
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
                .set(SUBMISSION.FIKS_DIGISOS_ID, fiksDigisosId)
                .set(SUBMISSION.KATEGORI, kategori)
                .onDuplicateKeyIgnore()
                .returning(SUBMISSION.ID)
                .fetchOne()
                ?.get(SUBMISSION.ID)

        if (insertedId != null) return insertedId

        // Update fiksDigisosId if not yet set on the existing row
        if (fiksDigisosId != null) {
            tx.dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.FIKS_DIGISOS_ID, fiksDigisosId)
                .where(
                    SUBMISSION.CONTEXT_ID.eq(contextId)
                        .and(SUBMISSION.OWNER_IDENT.eq(personIdent))
                        .and(SUBMISSION.FIKS_DIGISOS_ID.isNull),
                )
                .execute()
        }

        // Update kategori if not yet set on the existing row
        if (kategori != null) {
            tx.dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.KATEGORI, kategori)
                .where(
                    SUBMISSION.CONTEXT_ID.eq(contextId)
                        .and(SUBMISSION.OWNER_IDENT.eq(personIdent))
                        .and(SUBMISSION.KATEGORI.isNull),
                )
                .execute()
        }

        return tx
            .dsl()
            .select(SUBMISSION.ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.CONTEXT_ID.eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            )
            .fetchOne()
            ?.get(SUBMISSION.ID) ?: error("Could not find or create submission")
    }

    /**
     * Acquires a transaction-level Postgres advisory lock keyed on [fiksDigisosId].
     * The lock is automatically released when the surrounding transaction ends.
     *
     * The key is derived by XOR-ing the two 64-bit halves of the UUID, giving a
     * collision-free mapping within the UUID space.
     */
    fun acquireAdvisoryLock(
        tx: Configuration,
        fiksDigisosId: String,
    ) {
        val uuid = UUID.fromString(fiksDigisosId)
        val key = uuid.mostSignificantBits xor uuid.leastSignificantBits
        tx.dsl().query("SELECT pg_advisory_xact_lock({0})", param("key", key)).execute()
    }

    fun setNavEksternRefId(
        tx: Configuration,
        submissionId: UUID,
        navEksternRefId: String,
    ) {
        tx.dsl()
            .update(SUBMISSION)
            .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
            .where(SUBMISSION.ID.eq(submissionId).and(SUBMISSION.NAV_EKSTERN_REF_ID.isNull))
            .execute()
    }

    fun getNavEksternRefIdByContextId(
        tx: Configuration,
        contextId: String,
    ): String? =
        tx.dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(SUBMISSION.CONTEXT_ID.eq(contextId))
            .fetchOne()
            ?.get(SUBMISSION.NAV_EKSTERN_REF_ID)

    /**
     * Returns the highest [navEksternRefId] among all local submissions for [fiksDigisosId],
     * comparing the last 4 characters numerically. Returns null if no submissions exist yet.
     *
     * Used to seed the counter when generating a new navEksternRefId so that in-flight
     * submissions that haven't been submitted to Fiks yet are accounted for.
     */
    fun getMaxNavEksternRefIdForFiksDigisosId(
        tx: Configuration,
        fiksDigisosId: String,
    ): String? =
        tx.dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.FIKS_DIGISOS_ID.eq(fiksDigisosId)
                    .and(SUBMISSION.NAV_EKSTERN_REF_ID.isNotNull),
            )
            .fetch()
            .map { it[SUBMISSION.NAV_EKSTERN_REF_ID]!! }
            .maxByOrNull { it.takeLast(4).toLong() }

    private fun isOwnedByAnotherUser(
        tx: Configuration,
        contextId: String,
        personIdent: String,
    ): Boolean =
        tx.dsl()
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.CONTEXT_ID.eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.ne(personIdent)),
            )
            .fetchSingle()
            .value1() > 0
}
