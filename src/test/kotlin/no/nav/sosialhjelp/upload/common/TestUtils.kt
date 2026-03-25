package no.nav.sosialhjelp.upload.common

import no.nav.sosialhjelp.upload.database.generated.tables.Submission
import org.jooq.DSLContext
import java.util.UUID

object TestUtils {
    fun createMockSubmission(
        tx: DSLContext,
        contextId: String = UUID.randomUUID().toString(),
    ): UUID {
        val uuid = UUID.randomUUID()
        tx.transactionResult { it ->
            it
                .dsl()
                .insertInto(Submission.SUBMISSION)
                .set(Submission.SUBMISSION.ID, uuid)
                .set(Submission.SUBMISSION.OWNER_IDENT, "12345678910")
                .set(Submission.SUBMISSION.CONTEXT_ID, contextId)
                .set(Submission.SUBMISSION.NAV_EKSTERN_REF_ID, uuid.toString())
                .execute()
        }
        return uuid
    }
}
