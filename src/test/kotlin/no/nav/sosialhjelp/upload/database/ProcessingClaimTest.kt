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
import no.nav.sosialhjelp.upload.upload.MAX_PROCESSING_ATTEMPTS
import no.nav.sosialhjelp.upload.upload.ProcessingWorker
import no.nav.sosialhjelp.upload.upload.Status
import no.nav.sosialhjelp.upload.upload.UploadProcessingQueries
import no.nav.sosialhjelp.upload.upload.UploadProcessingService
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessingClaimTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
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
        val uploadId =
            dsl.transactionResult { tx ->
                TusUploadQueries().create(tx, submissionId, "a.pdf", 10L, null)
            }!!
        dsl
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.READY.name)
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
        return submissionId to uploadId
    }

    private fun claim(cutoff: OffsetDateTime = OffsetDateTime.now().minusMinutes(3)) =
        dsl.transactionResult { tx -> queries.claimNext(tx, cutoff) }

    private fun expireLease(uploadId: UUID) {
        dsl
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STARTED_AT, OffsetDateTime.now().minusMinutes(10))
            .where(UPLOAD.ID.eq(uploadId))
            .execute()
    }

    @Test
    fun `parallel workers claim a READY upload exactly once`() =
        runBlocking {
            readyUpload()
            val claims = (1..10).map { async(Dispatchers.IO) { claim() } }.awaitAll()
            assertEquals(1, claims.count { it != null })
        }

    @Test
    fun `active lease is not taken over`() {
        readyUpload()
        assertNotNull(claim())
        assertNull(claim())
    }

    @Test
    fun `expired lease is taken over and the old attempt is fenced out`() {
        val (_, uploadId) = readyUpload()
        val first = claim()!!
        expireLease(uploadId)
        val second = claim()!!
        assertEquals(2, second.attempt)

        dsl.transaction { tx ->
            assertFalse(queries.setFilId(tx, uploadId, first.attemptId, UUID.randomUUID(), "f", 1, "s", false, "x"))
            assertFalse(queries.markFailed(tx, uploadId, first.attemptId))
            assertTrue(queries.setFilId(tx, uploadId, second.attemptId, UUID.randomUUID(), "f", 1, "s", false, "x"))
        }
    }

    @Test
    fun `upload is not taken over after the last attempt`() {
        val (_, uploadId) = readyUpload()
        repeat(MAX_PROCESSING_ATTEMPTS) {
            assertNotNull(claim())
            expireLease(uploadId)
        }
        assertNull(claim())
    }

    @Test
    fun `parallel workers claim different READY uploads`() =
        runBlocking {
            val (_, first) = readyUpload()
            val (_, second) = readyUpload()
            // Each claim holds its row lock until the transaction ends, so run them concurrently
            // inside one outer transaction each and keep them open until both have claimed.
            val claims =
                (1..2)
                    .map {
                        async(Dispatchers.IO) {
                            dsl.transactionResult { tx ->
                                val claim = queries.claimNext(tx, OffsetDateTime.now().minusMinutes(3))
                                Thread.sleep(500)
                                claim
                            }
                        }
                    }.awaitAll()
            assertEquals(setOf(first, second), claims.mapNotNull { it?.uploadId }.toSet())
        }

    @Test
    fun `submit-locked submission does not block uploads in other submissions`() {
        val (lockedSubmission, _) = readyUpload()
        val (_, freeUpload) = readyUpload()
        assertTrue(
            dsl.transactionResult { tx -> submissionQueries.claimSubmit(tx, lockedSubmission, UUID.randomUUID()) },
        )
        assertEquals(freeUpload, claim()?.uploadId)
    }

    @Test
    fun `upload is not claimed while its submission is being submitted`() {
        val (submissionId, _) = readyUpload()
        assertTrue(dsl.transactionResult { tx -> submissionQueries.claimSubmit(tx, submissionId, UUID.randomUUID()) })
        assertNull(claim())
    }

    @Test
    fun `legacy processing is not reclaimed even after its old timestamp expires`() {
        val (_, legacyUpload) = readyUpload()
        dsl
            .update(UPLOAD)
            .set(UPLOAD.PROCESSING_STATUS, Status.PROCESSING.name)
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now().minusHours(1))
            .where(UPLOAD.ID.eq(legacyUpload))
            .execute()
        assertNull(claim())
        val (_, queuedUpload) = readyUpload()
        assertEquals(queuedUpload, claim()?.uploadId)
    }

    @Test
    fun `timed out processing frees worker for another upload`() =
        runBlocking {
            val service = mockk<UploadProcessingService>()
            val entered = CompletableDeferred<Unit>()
            val nextProcessed = CompletableDeferred<UUID>()
            val calls = AtomicInteger()
            coEvery { service.process(any()) } coAnswers {
                if (calls.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                nextProcessed.complete(firstArg<UploadProcessingQueries.Claim>().uploadId)
            }
            val worker = ProcessingWorker(dsl, queries, service, 300.milliseconds)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                readyUpload()
                worker.start(scope, workers = 1)
                withTimeout(10_000) { entered.await() }
                val (_, secondUpload) = readyUpload()
                worker.wake()
                assertEquals(secondUpload, withTimeout(10_000) { nextProcessed.await() })
            } finally {
                scope.cancel()
            }
        }
}
