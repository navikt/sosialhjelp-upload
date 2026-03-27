package no.nav.sosialhjelp.upload.status

import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.SubmissionRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.status.dto.SubmissionState
import no.nav.sosialhjelp.upload.status.dto.UploadDto
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionStatusServiceTest {
    private lateinit var submissionRepository: SubmissionRepository
    private lateinit var uploadRepository: UploadRepository
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var notificationService: SubmissionNotificationService

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        notificationService = SubmissionNotificationService(PostgresTestContainer.dataSource)
        submissionRepository = SubmissionRepository(dsl)
        uploadRepository = UploadRepository()
    }

    @BeforeEach
    fun cleanupBeforeEach() =
        dsl.transaction { config ->
            config.dsl().deleteFrom(UPLOAD).execute()
        }

    /**
     * Test that when no uploads exist for a given submission, the service returns a SubmissionState
     * with an empty uploads map.
     */
    @Test
    fun `getSubmissionStatus returns empty state when no uploads exist`() {
        val submissionId = createMockSubmission(dsl)
        val service = SubmissionService(uploadRepository, submissionRepository, dsl)

        // When: retrieving submission status
        val result: SubmissionState = service.getSubmissionStatus(submissionId)

        // Then: the returned state should have the matching submissionId and no uploads.
        assertEquals(submissionId.toString(), result.submissionId)
        assertTrue(result.uploads.isEmpty())
    }

    private fun createUpload(
        submissionId: UUID,
        filename: String,
    ) = dsl.transactionResult { it ->
        it
            .dsl()
            .insertInto(
                UPLOAD,
            ).set(
                UPLOAD.ID,
                UUID.randomUUID(),
            ).set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .returning(UPLOAD.ID)
            .fetchSingle()[UPLOAD.ID]
    }

    /**
     * Test that multiple uploads for the same submission are all
     * correctly returned in the SubmissionState.
     */
    @Test
    fun `getSubmissionStatus returns multiple uploads each with their corresponding pages`() {
        // Given
        val submissionId = createMockSubmission(dsl)

        val uploadId1 =
            createUpload(submissionId, "first.pdf")
        val uploadId2 =
            createUpload(submissionId, "second.pdf")

        val service = SubmissionService(uploadRepository, submissionRepository, dsl)

        // When
        val result: SubmissionState = service.getSubmissionStatus(submissionId)

        // Then
        assertEquals(submissionId.toString(), result.submissionId)
        assertEquals(2, result.uploads.size)
        val firstUpload: UploadDto? = result.uploads.find { it.id == uploadId1 }
        val secondUpload: UploadDto? = result.uploads.find { it.id == uploadId2 }
        assertNotNull(firstUpload)
        assertNotNull(secondUpload)
        assertEquals("first.pdf", firstUpload.originalFilename)
        assertEquals("second.pdf", secondUpload.originalFilename)
    }
}
