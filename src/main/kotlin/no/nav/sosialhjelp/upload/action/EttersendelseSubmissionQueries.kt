package no.nav.sosialhjelp.upload.action

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import org.jooq.Configuration
import java.util.UUID

/**
 * DB queries for the ettersendelse submission flow.
 */
class EttersendelseSubmissionQueries {
    fun getNavEksternRefId(
        tx: Configuration,
        submissionId: UUID,
        personIdent: String,
    ): String =
        tx
            .dsl()
            .select(SUBMISSION.NAV_EKSTERN_REF_ID)
            .from(SUBMISSION)
            .where(
                SUBMISSION.ID.eq(submissionId)
                    .and(SUBMISSION.OWNER_IDENT.eq(personIdent)),
            )
            .fetchOne()
            ?.get(SUBMISSION.NAV_EKSTERN_REF_ID)
            ?: error("Could not find submission $submissionId for personIdent")
}
