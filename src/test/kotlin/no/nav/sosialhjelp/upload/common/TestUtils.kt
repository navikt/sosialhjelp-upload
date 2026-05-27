package no.nav.sosialhjelp.upload.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
import no.nav.sosialhjelp.upload.database.generated.tables.Submission
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    suspend fun awaitUploadTerminal(
        dsl: DSLContext,
        uploadId: UUID,
        timeout: Duration = 10.seconds,
        notifications: SubmissionNotificationService = PostgresTestContainer.notificationService,
    ) {
        CoroutineScope(Dispatchers.IO).async {
            withTimeout(timeout) {
                fun currentStatus(): String? =
                    dsl.select(UPLOAD.PROCESSING_STATUS)
                        .from(UPLOAD)
                        .where(UPLOAD.ID.eq(uploadId))
                        .fetchOne()
                        ?.value1()

                fun isTerminal(s: String?) = s == "COMPLETE" || s == "FAILED"

                val channel = Channel<Unit>(capacity = Channel.UNLIMITED)
                val collector = notifications.allUpdates
                    .onEach { channel.trySend(Unit) }
                    .launchIn(this)
                try {
                    if (isTerminal(currentStatus())) return@withTimeout
                    for (notification in channel) {
                        if (isTerminal(currentStatus())) return@withTimeout
                    }
                } finally {
                    collector.cancel()
                    channel.close()
                }
            }
        }.await()
    }
}
