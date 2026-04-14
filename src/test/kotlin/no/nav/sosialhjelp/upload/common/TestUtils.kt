package no.nav.sosialhjelp.upload.common

import no.nav.sosialhjelp.upload.database.generated.tables.Submission
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
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

    /**
     * Blocks (using Thread.sleep) until the given upload reaches a terminal status (COMPLETE or FAILED),
     * or throws if the timeout elapses. Safe to call from coroutine tests — uses real time.
     */
    fun awaitUploadTerminal(dsl: DSLContext, uploadId: UUID, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = dsl.select(UPLOAD.PROCESSING_STATUS)
                .from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchOne()
                ?.value1()
            if (status == "COMPLETE" || status == "FAILED") return
            Thread.sleep(100)
        }
        error("Upload $uploadId did not reach COMPLETE or FAILED within ${timeoutMs}ms")
    }
}
