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
                SUBMISSION.CONTEXT_ID
                    .eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            ).fetchOne()
            ?.get(SUBMISSION.ID)
    }

    /**
     * Attributes carried by a TUS upload that describe the submission it belongs to.
     */
    data class SubmissionAttributes(
        val fiksDigisosId: String? = null,
        val kategori: String? = null,
        val automaticCleanup: Boolean = false,
    )

    /**
     * Gets an existing submission for [contextId] + [personIdent], or creates one if none exists.
     * Updates fiksDigisosId and kategori on the existing row if they were null.
     * Throws [SubmissionOwnedByAnotherUserException] if the contextId is owned by someone else.
     *
     * `automaticCleanup` follows "false wins": it can be turned off by a later upload on the same
     * contextId, but never turned back on. A single upload with inconsistent metadata must not be
     * able to enable automatic deletion of everything uploaded under this submission.
     */
    fun getOrCreateSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        attributes: SubmissionAttributes = SubmissionAttributes(),
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
                .set(SUBMISSION.FIKS_DIGISOS_ID, attributes.fiksDigisosId)
                .set(SUBMISSION.KATEGORI, attributes.kategori)
                .set(SUBMISSION.AUTOMATIC_CLEANUP, attributes.automaticCleanup)
                .onDuplicateKeyIgnore()
                .returning(SUBMISSION.ID)
                .fetchOne()
                ?.get(SUBMISSION.ID)

        if (insertedId != null) return insertedId

        updateExistingSubmission(tx, contextId, personIdent, attributes)

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

    private fun updateExistingSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        attributes: SubmissionAttributes,
    ) {
        val identifiesSubmission =
            SUBMISSION.CONTEXT_ID
                .eq(contextId)
                .and(SUBMISSION.OWNER_IDENT.eq(personIdent))

        // Set fiksDigisosId and kategori if they were not set by the first upload
        if (attributes.fiksDigisosId != null) {
            tx
                .dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.FIKS_DIGISOS_ID, attributes.fiksDigisosId)
                .where(identifiesSubmission.and(SUBMISSION.FIKS_DIGISOS_ID.isNull))
                .execute()
        }

        if (attributes.kategori != null) {
            tx
                .dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.KATEGORI, attributes.kategori)
                .where(identifiesSubmission.and(SUBMISSION.KATEGORI.isNull))
                .execute()
        }

        // "False wins": an upload that opts out disables automatic cleanup for the whole
        // submission. The reverse transition is deliberately not possible, so that a single
        // upload with inconsistent metadata can never enable deletion of the others.
        if (!attributes.automaticCleanup) {
            tx
                .dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.AUTOMATIC_CLEANUP, false)
                .where(identifiesSubmission)
                .execute()
        }
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
        tx
            .dsl()
            .update(SUBMISSION)
            .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
            .where(SUBMISSION.ID.eq(submissionId).and(SUBMISSION.NAV_EKSTERN_REF_ID.isNull))
            .execute()
    }

    fun getNavEksternRefIdByContextId(
        tx: Configuration,
        contextId: String,
    ): String? =
        tx
            .dsl()
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
        tx
            .dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.FIKS_DIGISOS_ID
                    .eq(fiksDigisosId)
                    .and(SUBMISSION.NAV_EKSTERN_REF_ID.isNotNull),
            ).fetch()
            .map { it[SUBMISSION.NAV_EKSTERN_REF_ID]!! }
            .maxByOrNull { it.takeLast(4).toLong() }

    private fun isOwnedByAnotherUser(
        tx: Configuration,
        contextId: String,
        personIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.CONTEXT_ID
                    .eq(contextId)
                    .and(SUBMISSION.OWNER_IDENT.ne(personIdent)),
            ).fetchSingle()
            .value1() > 0
}
