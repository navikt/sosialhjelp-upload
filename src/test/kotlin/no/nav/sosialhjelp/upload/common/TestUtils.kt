package no.nav.sosialhjelp.upload.common

import no.nav.sosialhjelp.upload.database.generated.tables.Submission
import org.jooq.DSLContext
import java.util.UUID

object TestUtils {
    fun createMockSubmission(
        tx: DSLContext,
        contextId: String = UUID.randomUUID().toString(),
        ownerIdent: String = "12345678910",
        navEksternRefId: String? = null,
    ): UUID {
        val uuid = UUID.randomUUID()
        tx.transactionResult { it ->
            it
                .dsl()
                .insertInto(Submission.SUBMISSION)
                .set(Submission.SUBMISSION.ID, uuid)
                .set(Submission.SUBMISSION.OWNER_IDENT, ownerIdent)
                .set(Submission.SUBMISSION.CONTEXT_ID, contextId)
                .set(Submission.SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId ?: uuid.toString())
                .execute()
        }
        return uuid
    }
}
