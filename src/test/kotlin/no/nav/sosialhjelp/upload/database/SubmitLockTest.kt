package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmitLockTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private val queries by lazy { SubmissionQueries(dsl) }
    private val tusQueries = TusSubmissionQueries()

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
    }

    @BeforeEach
    fun clean() {
        dsl.deleteFrom(SUBMISSION).execute()
    }

    private fun claim(
        id: UUID,
        attempt: UUID,
    ) = dsl.transactionResult { tx -> queries.claimSubmit(tx, id, attempt) }

    private fun backdateLock(id: UUID) {
        dsl
            .update(SUBMISSION)
            .set(SUBMISSION.SUBMIT_STARTED_AT, OffsetDateTime.now().minusMinutes(SUBMIT_LOCK_MINUTES + 1))
            .where(SUBMISSION.ID.eq(id))
            .execute()
    }

    @Test
    fun `parallel claims give exactly one winner`() =
        runBlocking {
            val id = createMockSubmission(dsl)
            val results =
                (1..20)
                    .map { async(Dispatchers.IO) { claim(id, UUID.randomUUID()) } }
                    .awaitAll()
            assertEquals(1, results.count { it })
        }

    @Test
    fun `second claim is rejected while the lock is active`() {
        val id = createMockSubmission(dsl)
        assertTrue(claim(id, UUID.randomUUID()))
        assertFalse(claim(id, UUID.randomUUID()))
    }

    @Test
    fun `expired lock can be taken over and the old attempt is fenced out`() {
        val id = createMockSubmission(dsl)
        val old = UUID.randomUUID()
        val new = UUID.randomUUID()
        assertTrue(claim(id, old))
        backdateLock(id)

        assertTrue(claim(id, new))

        dsl.transaction { tx ->
            assertFalse(queries.isCurrentAttempt(tx, id, old))
            assertTrue(queries.isCurrentAttempt(tx, id, new))
            assertFalse(queries.releaseSubmit(tx, id, old))
            assertEquals(0, queries.markSubmitted(tx, id, old))
        }
        assertEquals(1, dsl.fetchCount(SUBMISSION, SUBMISSION.ID.eq(id)))
    }

    @Test
    fun `release lets a new attempt claim immediately`() {
        val id = createMockSubmission(dsl)
        val attempt = UUID.randomUUID()
        assertTrue(claim(id, attempt))
        assertTrue(dsl.transactionResult { tx -> queries.releaseSubmit(tx, id, attempt) })
        assertTrue(claim(id, UUID.randomUUID()))
    }

    @Test
    fun `markSubmitted keeps the row, frees the context id and blocks a new submit`() {
        val contextId = UUID.randomUUID().toString()
        val id = createMockSubmission(dsl, contextId)
        val attempt = UUID.randomUUID()
        assertTrue(claim(id, attempt))
        assertEquals(1, dsl.transactionResult { tx -> queries.markSubmitted(tx, id, attempt) })

        // Kept so the ref still counts when the next navEksternRefId is derived.
        assertEquals(1, dsl.fetchCount(SUBMISSION, SUBMISSION.ID.eq(id)))
        assertEquals(0, dsl.fetchCount(SUBMISSION, SUBMISSION.CONTEXT_ID.eq(contextId)))
        assertFalse(claim(id, UUID.randomUUID()))
    }

    @Test
    fun `next navEksternRefId counts a submitted submission`() {
        val fiksDigisosId = UUID.randomUUID().toString()
        val id = createMockSubmission(dsl, navEksternRefId = "${fiksDigisosId}0007")
        dsl
            .update(SUBMISSION)
            .set(SUBMISSION.FIKS_DIGISOS_ID, fiksDigisosId)
            .where(SUBMISSION.ID.eq(id))
            .execute()
        val attempt = UUID.randomUUID()
        assertTrue(claim(id, attempt))
        dsl.transaction { tx -> queries.markSubmitted(tx, id, attempt) }

        assertEquals(
            "${fiksDigisosId}0007",
            dsl.transactionResult { tx -> tusQueries.getMaxNavEksternRefIdForFiksDigisosId(tx, fiksDigisosId) },
        )
    }

    @Test
    fun `isSubmitLocked reflects active and expired locks`() {
        val id = createMockSubmission(dsl)
        assertFalse(dsl.transactionResult { tx -> tusQueries.isSubmitLocked(tx, id) })
        claim(id, UUID.randomUUID())
        assertTrue(dsl.transactionResult { tx -> tusQueries.isSubmitLocked(tx, id) })
        backdateLock(id)
        assertFalse(dsl.transactionResult { tx -> tusQueries.isSubmitLocked(tx, id) })
    }
}
