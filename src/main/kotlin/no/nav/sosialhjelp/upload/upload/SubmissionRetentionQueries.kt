package no.nav.sosialhjelp.upload.upload

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import org.jooq.Configuration
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

/**
 * DB queries for the submission retention job: finding stale submissions to clean up.
 */
class SubmissionRetentionQueries {

    data class StaleSubmission(val id: UUID, val navEksternRefId: String)

    /**
     * Returns submissions whose uploads have all reached a terminal state (not PENDING or PROCESSING)
     * and whose last upload activity was before [cutoff].
     */
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
