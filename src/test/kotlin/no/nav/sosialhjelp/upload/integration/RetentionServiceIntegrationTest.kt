package no.nav.sosialhjelp.upload.integration

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.RetentionService
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionServiceIntegrationTest {

    private val dsl: DSLContext = PostgresTestContainer.dsl
    private val submissionRepository = SubmissionRepository(dsl)
    private val uploadRepository = UploadRepository()
    private val mellomlagringClient = mockk<MellomlagringClient>(relaxed = true)
    private val chunkStorage = FileSystemStorage()

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(UPLOAD).execute()
        dsl.deleteFrom(SUBMISSION).execute()
    }

    private fun retentionService(timeout: Duration = Duration.ofSeconds(1)) =
        RetentionService(dsl, submissionRepository, uploadRepository, mellomlagringClient, chunkStorage, mockk(relaxed = true), SimpleMeterRegistry(), timeout)

    @Test
    fun `stale submissions are deleted after retention period`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        // Insert a COMPLETE upload with updated_at in the past
        val uploadId = UUID.randomUUID()
        dsl.insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, "test.pdf")
            .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now().minusSeconds(5))
            .execute()

        runBlocking { retentionService().runRetention() }

        val sub = dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()
        assertNull(sub, "Submission should be deleted after retention run")

        coVerify { mellomlagringClient.deleteMellomlagring(navEksternRefId) }
    }

    @Test
    fun `submissions not yet past retention period are kept`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        val uploadId = UUID.randomUUID()
        dsl.insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, "test.pdf")
            .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now())
            .execute()

        // Use a long retention timeout so the submission is NOT stale yet
        runBlocking { retentionService(Duration.ofHours(1)).runRetention() }

        val sub = dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()
        kotlin.test.assertNotNull(sub, "Submission should still exist when not past retention period")
    }

    @Test
    fun `pending uploads prevent submission from being cleaned up`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        val uploadId = UUID.randomUUID()
        dsl.insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, "test.pdf")
            .set(UPLOAD.PROCESSING_STATUS, "PENDING")
            .set(UPLOAD.UPDATED_AT, OffsetDateTime.now().minusSeconds(5))
            .execute()

        runBlocking { retentionService().runRetention() }

        val sub = dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()
        kotlin.test.assertNotNull(sub, "Submission with PENDING uploads should not be cleaned up")
    }
}
