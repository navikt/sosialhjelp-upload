package no.nav.sosialhjelp.upload.database

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import org.jooq.Configuration
import org.jooq.DSLContext
import java.util.UUID

/**
 * Shared submission DB queries used across multiple features:
 * ownership verification (interceptors) and submission cleanup (action + retention).
 */
class SubmissionQueries(
    private val dsl: DSLContext,
) {
    /**
     * Ownership check for the submission interceptor — uses DSLContext directly
     * since it is called outside a transaction in the route plugin.
     */
    fun isOwnedByUser(id: UUID, personIdent: String): Boolean =
        dsl
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.ID.eq(id)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            )
            .fetchOne()
            ?.value1()
            .let { (it ?: 0) != 0 }

    fun isNavEksternRefIdOwnedByUser(
        tx: Configuration,
        navEksternRefId: String,
        personIdent: String,
    ): Boolean =
        tx
            .dsl()
            .selectCount()
            .from(SUBMISSION)
            .where(
                SUBMISSION.NAV_EKSTERN_REF_ID.eq(navEksternRefId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            )
            .fetchSingle()
            .value1() > 0

    fun cleanup(tx: Configuration, submissionId: UUID): Int =
        tx
            .dsl()
            .deleteFrom(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .execute()
}
