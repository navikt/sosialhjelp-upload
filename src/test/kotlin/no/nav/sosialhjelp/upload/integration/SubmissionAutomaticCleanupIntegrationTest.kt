package no.nav.sosialhjelp.upload.integration

import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The automatic_cleanup flag decides whether this service is allowed to delete a submission on its
 * own. Getting the default and the conflict resolution wrong means losing user attachments, so both
 * are pinned down here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionAutomaticCleanupIntegrationTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private val queries = TusSubmissionQueries()

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(UPLOAD).execute()
        dsl.deleteFrom(SUBMISSION).execute()
    }

    private fun automaticCleanupFor(submissionId: UUID): Boolean =
        dsl
            .select(SUBMISSION.AUTOMATIC_CLEANUP)
            .from(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .fetchSingle()
            .value1()!!

    private fun getOrCreate(
        contextId: String,
        automaticCleanup: Boolean,
    ): UUID =
        dsl.transactionResult { tx ->
            queries.getOrCreateSubmission(
                tx,
                contextId = contextId,
                personIdent = "12345678910",
                attributes = TusSubmissionQueries.SubmissionAttributes(automaticCleanup = automaticCleanup),
            )
        }

    @Test
    fun `defaults to false so that nothing is deleted unless explicitly opted in`() {
        val contextId = UUID.randomUUID().toString()
        val submissionId =
            dsl.transactionResult { tx ->
                queries.getOrCreateSubmission(tx, contextId = contextId, personIdent = "12345678910")
            }

        assertFalse(automaticCleanupFor(submissionId))
    }

    @Test
    fun `opting in is persisted`() {
        val submissionId = getOrCreate(UUID.randomUUID().toString(), automaticCleanup = true)

        assertTrue(automaticCleanupFor(submissionId))
    }

    @Test
    fun `false wins when a later upload on the same contextId opts out`() {
        val contextId = UUID.randomUUID().toString()
        val first = getOrCreate(contextId, automaticCleanup = true)
        val second = getOrCreate(contextId, automaticCleanup = false)

        assertEquals(first, second, "Same contextId should yield the same submission")
        assertFalse(automaticCleanupFor(first), "An upload that opts out must disable cleanup for the submission")
    }

    @Test
    fun `a later upload cannot turn automatic cleanup back on`() {
        val contextId = UUID.randomUUID().toString()
        val first = getOrCreate(contextId, automaticCleanup = false)
        getOrCreate(contextId, automaticCleanup = true)

        assertFalse(automaticCleanupFor(first), "Automatic cleanup must never be re-enabled by a later upload")
    }
}
