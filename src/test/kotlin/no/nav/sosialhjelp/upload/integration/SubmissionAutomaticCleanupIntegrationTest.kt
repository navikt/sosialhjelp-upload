package no.nav.sosialhjelp.upload.integration

import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.status.SubmissionService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusSubmissionQueries
import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `automatic_cleanup` decides whether this service is allowed to delete a submission on its own.
 * Getting the state transitions wrong either loses user attachments or leaks them forever, so every
 * transition is pinned down here.
 *
 * The column is a three-state machine: null (no upload has spoken yet), true (opted in), false
 * (opted out). Only null -> true and -> false are allowed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionAutomaticCleanupIntegrationTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private val queries = TusSubmissionQueries()
    private val personIdent = "12345678910"

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(UPLOAD).execute()
        dsl.deleteFrom(SUBMISSION).execute()
    }

    private fun automaticCleanupFor(submissionId: UUID): Boolean? =
        dsl
            .select(SUBMISSION.AUTOMATIC_CLEANUP)
            .from(SUBMISSION)
            .where(SUBMISSION.ID.eq(submissionId))
            .fetchSingle()
            .value1()

    /** Mimics a TUS upload arriving with the given metadata value. */
    private fun upload(
        contextId: String,
        automaticCleanup: Boolean,
    ): UUID =
        dsl.transactionResult { tx ->
            queries.getOrCreateSubmission(
                tx,
                contextId = contextId,
                personIdent = personIdent,
                attributes = TusSubmissionQueries.SubmissionAttributes(automaticCleanup = automaticCleanup),
            )
        }

    /** Mimics the SSE status route, which creates the submission without deciding the policy. */
    private fun openStatusStream(contextId: String): UUID =
        runBlocking {
            SubmissionService(UploadRepository(), queries, dsl).getOrCreate(contextId, personIdent)
        }

    @Test
    fun `a submission created by the status route has no opinion yet`() {
        val submissionId = openStatusStream(UUID.randomUUID().toString())

        assertNull(
            automaticCleanupFor(submissionId),
            "The SSE route knows nothing about the flow and must not decide the cleanup policy",
        )
    }

    /**
     * The real frontend order: the status stream is opened when the page loads, before any file is
     * uploaded. If the row created there blocks the later opt-in, retention never runs for
     * ettersendelser and mellomlagring leaks forever.
     */
    @Test
    fun `an upload can opt in after the status route has created the submission`() {
        val contextId = UUID.randomUUID().toString()
        val fromStatusRoute = openStatusStream(contextId)
        val fromUpload = upload(contextId, automaticCleanup = true)

        assertEquals(fromStatusRoute, fromUpload, "Same contextId should yield the same submission")
        assertTrue(automaticCleanupFor(fromUpload)!!, "The first upload must be able to opt in")
    }

    @Test
    fun `an upload can opt out after the status route has created the submission`() {
        val contextId = UUID.randomUUID().toString()
        openStatusStream(contextId)
        val submissionId = upload(contextId, automaticCleanup = false)

        assertFalse(automaticCleanupFor(submissionId)!!)
    }

    @Test
    fun `the first upload persists its choice when it creates the submission`() {
        assertTrue(automaticCleanupFor(upload(UUID.randomUUID().toString(), automaticCleanup = true))!!)
        assertFalse(automaticCleanupFor(upload(UUID.randomUUID().toString(), automaticCleanup = false))!!)
    }

    @Test
    fun `false wins when a later upload on the same contextId opts out`() {
        val contextId = UUID.randomUUID().toString()
        val first = upload(contextId, automaticCleanup = true)
        val second = upload(contextId, automaticCleanup = false)

        assertEquals(first, second)
        assertFalse(
            automaticCleanupFor(first)!!,
            "An upload that opts out must disable cleanup for the whole submission",
        )
    }

    @Test
    fun `a later upload cannot turn automatic cleanup back on`() {
        val contextId = UUID.randomUUID().toString()
        val submissionId = upload(contextId, automaticCleanup = false)
        upload(contextId, automaticCleanup = true)

        assertFalse(
            automaticCleanupFor(submissionId)!!,
            "false is absorbing — automatic cleanup must never be re-enabled by a later upload",
        )
    }

    @Test
    fun `the status route does not reset a choice already made by an upload`() {
        val contextId = UUID.randomUUID().toString()
        val submissionId = upload(contextId, automaticCleanup = true)

        openStatusStream(contextId)

        assertTrue(automaticCleanupFor(submissionId)!!, "Opening the status stream must not change the policy")
    }
}
