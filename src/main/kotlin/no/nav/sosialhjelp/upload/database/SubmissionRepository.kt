package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.*
import org.jooq.impl.DSL.param
class SubmissionRepository(
    private val dsl: DSLContext,
) {
    class SubmissionOwnedByAnotherUserException : RuntimeException()

    data class StaleSubmission(val id: UUID, val navEksternRefId: String)

    fun findSubmission(tx: Configuration, contextId: String, personIdent: String): UUID? {
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

    fun setNavEksternRefId(tx: Configuration, submissionId: UUID, navEksternRefId: String) {
        tx
            .dsl()
            .update(SUBMISSION)
            .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
            .where(SUBMISSION.ID.eq(submissionId).and(SUBMISSION.NAV_EKSTERN_REF_ID.isNull))
            .execute()
    }

    fun getNavEksternRefIdByContextId(tx: Configuration, contextId: String): String? =
        tx
            .dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(SUBMISSION.CONTEXT_ID.eq(contextId))
            .fetchOne()
            ?.get(SUBMISSION.NAV_EKSTERN_REF_ID)

    /**
     * Returns the highest [navEksternRefId] among all local submissions for the given [fiksDigisosId],
     * comparing the last 4 characters numerically. Returns null if no submissions exist yet for this id.
     *
     * This is used to seed the counter when generating a new navEksternRefId, so that in-flight
     * submissions that haven't been submitted to Fiks yet are accounted for.
     */
    fun getMaxNavEksternRefIdForFiksDigisosId(tx: Configuration, fiksDigisosId: String): String? =
        tx
            .dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.FIKS_DIGISOS_ID.eq(fiksDigisosId)
                    .and(SUBMISSION.NAV_EKSTERN_REF_ID.isNotNull),
            )
            .fetch()
            .map { it[SUBMISSION.NAV_EKSTERN_REF_ID]!! }
            .maxByOrNull { it.takeLast(4).toLong() }

    /**
     * Acquires a transaction-level Postgres advisory lock keyed on [fiksDigisosId].
     * The lock is automatically released when the surrounding transaction ends.
     *
     * The key is derived by XOR-ing the two 64-bit halves of the UUID, giving a
     * collision-free mapping within the UUID space.
     */
    fun acquireAdvisoryLock(tx: Configuration, fiksDigisosId: String) {
        val uuid = UUID.fromString(fiksDigisosId)
        val key = uuid.mostSignificantBits xor uuid.leastSignificantBits
        tx.dsl().query("SELECT pg_advisory_xact_lock({0})", param("key", key)).execute()
    }

    fun getOrCreateSubmission(
        tx: Configuration,
        contextId: String,
        personIdent: String,
        fiksDigisosId: String? = null,
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
                .onDuplicateKeyIgnore()
                .returning(SUBMISSION.ID)
                .fetchOne()
                ?.get(SUBMISSION.ID)

        if (insertedId != null) return insertedId

        // update fiksDigisosId if not set
        if (fiksDigisosId != null) {
            tx
                .dsl()
                .update(SUBMISSION)
                .set(SUBMISSION.FIKS_DIGISOS_ID, fiksDigisosId)
                .where(
                    SUBMISSION.CONTEXT_ID
                        .eq(contextId)
                        .and(SUBMISSION.OWNER_IDENT.eq(personIdent))
                        .and(SUBMISSION.FIKS_DIGISOS_ID.isNull),
                ).execute()
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

