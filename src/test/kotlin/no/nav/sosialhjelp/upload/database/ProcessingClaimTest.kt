package no.nav.sosialhjelp.upload.database

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusUploadQueries
import no.nav.sosialhjelp.upload.upload.ProcessingWorker
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.UploadProcessingQueries
import no.nav.sosialhjelp.upload.upload.UploadProcessingService
import no.nav.sosialhjelp.upload.upload.UploadRecoveryQueries
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessingClaimTest {
    private val dsl = PostgresTestContainer.dsl
    private val queries = UploadProcessingQueries()
    private val submissionQueries by lazy { SubmissionQueries(dsl) }

    @BeforeAll
    fun setup() = PostgresTestContainer.migrate()

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SUBMISSION).execute()
    }

    private fun readyUpload(): Pair<UUID, UUID> {
        val submissionId = createMockSubmission(dsl)
        val uploadId = dsl.transactionResult { tx -> TusUploadQueries().create(tx, submissionId, "a.pdf", 10L, null) }!!
        dsl
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.READY.name)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
        return submissionId to uploadId
    }

    private fun claim() = dsl.transactionResult { tx -> queries.claimNext(tx) }

    @Test
    fun `parallel workers claim a queued upload only once`() =
        runBlocking {
            readyUpload()
            val claims = (1..10).map { async(Dispatchers.IO) { claim() } }.awaitAll()
            assertEquals(1, claims.count { it != null })
        }

    @Test
    fun `abandoned processing fails rather than being claimed again`() {
        val (_, uploadId) = readyUpload()
        assertEquals(uploadId, claim())
        dsl
            .update(UPLOAD)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now().minusMinutes(10))
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
        assertNull(claim())
        assertEquals(
            1,
            dsl.transactionResult { tx ->
                UploadRecoveryQueries().markStaleProcessingAsFailed(tx, OffsetDateTime.now().minusMinutes(3)).size
            },
        )
        val status =
            dsl
                .select(
                    UPLOAD.PROCESSING_STATUS,
                ).from(UPLOAD)
                .where(UPLOAD.ID.eq(uploadId))
                .fetchSingle()
                .value1()
        assertEquals("FAILED", status)
        assertFalse(
            dsl.transactionResult { tx ->
                queries.setFilId(tx, uploadId, UUID.randomUUID(), "f", 1, "s", false, "x")
            },
        )
    }

    @Test
    fun `workers skip submissions while they are being submitted`() {
        val (submissionId, _) = readyUpload()
        assertTrue(dsl.transactionResult { tx -> submissionQueries.claimSubmit(tx, submissionId, UUID.randomUUID()) })
        assertNull(claim())
        val (_, freeUpload) = readyUpload()
        assertEquals(freeUpload, claim())
    }

    @Test
    fun `timeout fails the upload and frees the worker`() =
        runBlocking {
            val service = mockk<UploadProcessingService>()
            val entered = CompletableDeferred<UUID>()
            val failed = CompletableDeferred<UUID>()
            coEvery { service.process(any()) } coAnswers {
                entered.complete(firstArg())
                awaitCancellation()
            }
            coEvery { service.markTimedOut(any()) } coAnswers {
                failed.complete(firstArg())
            }
            val worker = ProcessingWorker(dsl, queries, service, 300.milliseconds)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val (_, uploadId) = readyUpload()
                worker.start(scope, workers = 1)
                assertEquals(uploadId, withTimeout(10_000) { entered.await() })
                assertEquals(uploadId, withTimeout(10_000) { failed.await() })
                assertNotNull(dsl.selectFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).fetchOne())
            } finally {
                scope.cancel()
            }
        }
}
