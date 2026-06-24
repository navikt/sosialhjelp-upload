package no.nav.sosialhjelp.upload.database

import io.mockk.every
import io.mockk.mockk
import no.nav.sosialhjelp.upload.common.TestUtils
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.notify.SubmissionNotificationService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.TusUploadQueries
import no.nav.sosialhjelp.upload.upload.UploadNotifications
import no.nav.sosialhjelp.upload.upload.UploadRecoveryQueries
import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRepositoryTest {
    private lateinit var tusUploadQueries: TusUploadQueries
    private lateinit var uploadRepository: UploadRepository
    private lateinit var uploadRecoveryQueries: UploadRecoveryQueries
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var notificationServiceMock: SubmissionNotificationService

    @BeforeAll
    fun setupDatabase() {
        PostgresTestContainer.migrate()
        notificationServiceMock = mockk<SubmissionNotificationService>()
        tusUploadQueries = TusUploadQueries()
        uploadRepository = UploadRepository()
        uploadRecoveryQueries = UploadRecoveryQueries()
    }

    @BeforeEach
    fun cleanup() {
        dsl.transaction { config ->
            config.dsl().deleteFrom(SUBMISSION).execute()
        }
    }

    @Test
    fun `test create upload`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        val filename = "testfile.txt"
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId = dsl.transactionResult { tx -> tusUploadQueries.create(tx, submissionId, filename, 10L) }

        dsl.transaction { tx ->
            val upload =
                tx
                    .dsl()
                    .select(
                        UPLOAD.SUBMISSION_ID,
                        UPLOAD.ORIGINAL_FILENAME,
                    ).from(UPLOAD)
                    .where(UPLOAD.ID.eq(uploadId!!))
                    .fetchSingle()
            assertEquals(submissionId, upload[UPLOAD.SUBMISSION_ID])
            assertEquals(filename, upload[UPLOAD.ORIGINAL_FILENAME])
        }
    }

    @Test
    fun `test getUploadsByDocumentId returns correct uploads`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId1 =
            dsl.transactionResult { config ->
                tusUploadQueries.create(config, submissionId, "file1.txt", 10L)
            }
        val uploadId2 =
            dsl.transactionResult { config ->
                tusUploadQueries.create(config, submissionId, "file2.txt", 190L)
            }
        // Create an upload for a different submission to ensure filtering works.
        val otherSubmissionId = TestUtils.createMockSubmission(dsl)
        dsl.transaction { config -> tusUploadQueries.create(config, otherSubmissionId, "otherfile.txt", 19L) }

        val uploads =
            dsl
                .transactionResult { tx ->
                    uploadRepository.getUploads(tx, submissionId).toList()
                }.map { it.id }
        assertTrue(uploads.contains(uploadId1))
        assertTrue(uploads.contains(uploadId2))
        assertEquals(2, uploads.size)
    }

    @Test
    fun `test getSubmissionIdFromUploadId returns correct submission id`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId =
            dsl.transactionResult { config ->
                tusUploadQueries.create(config, submissionId, "file.txt", 10L)
            } ?: error("Ingen uploadId")
        val retrievedSubmissionId =
            dsl.transactionResult { config ->
                tusUploadQueries.getSubmissionIdFromUploadId(config, uploadId)
            }
        assertEquals(submissionId, retrievedSubmissionId)
    }

    @Test
    fun `test notifyChange does not throw`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId =
            dsl.transactionResult { config ->
                tusUploadQueries.create(config, submissionId, "notify.txt", 10L)
            } ?: error("Ingen uploadId")

        // notifyChange now sends pg_notify within the transaction; just verify it doesn't throw
        dsl.transaction { config -> UploadNotifications.notifyChange(config, uploadId) }
    }

    @Test
    fun `markStaleProcessingAsFailed marks stuck uploads as failed`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId =
            dsl.transactionResult { tx ->
                tusUploadQueries.create(tx, submissionId, "stuck.txt", 100L)
            } ?: error("No uploadId")

        // Set PROCESSING state with an old updated_at
        dsl.execute(
            "UPDATE upload SET processing_status = 'PROCESSING', updated_at = NOW() - INTERVAL '10 minutes'" +
                " WHERE id = ?",
            uploadId,
        )

        val cutoff = java.time.OffsetDateTime.now()
        val staleUploads =
            dsl.transactionResult { tx ->
                uploadRecoveryQueries.markStaleProcessingAsFailed(tx, cutoff)
            }

        assertEquals(listOf(submissionId), staleUploads.map { it.submissionId })
        dsl.transaction { tx ->
            val record =
                tx.dsl()
                    .select(UPLOAD.PROCESSING_STATUS)
                    .from(UPLOAD)
                    .where(UPLOAD.ID.eq(uploadId))
                    .fetchSingle()
            assertEquals("FAILED", record[UPLOAD.PROCESSING_STATUS])
        }
    }

    @Test
    fun `markHaltedPendingAsFailed clears stalled uploads`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId =
            dsl.transactionResult { tx ->
                tusUploadQueries.create(tx, submissionId, "halted.txt", 100L)
            } ?: error("No uploadId")

        // Set upload_offset > 0 and old updated_at
        dsl.execute(
            "UPDATE upload SET upload_offset = 50, updated_at = NOW() - INTERVAL '2 hours' WHERE id = ?",
            uploadId,
        )

        val cutoff = java.time.OffsetDateTime.now()
        val staleUploads =
            dsl.transactionResult { tx ->
                uploadRecoveryQueries.markHaltedPendingAsFailed(tx, cutoff)
            }

        assertEquals(listOf(submissionId), staleUploads.map { it.submissionId })
        dsl.transaction { tx ->
            val record =
                tx.dsl()
                    .select(UPLOAD.PROCESSING_STATUS)
                    .from(UPLOAD)
                    .where(UPLOAD.ID.eq(uploadId))
                    .fetchSingle()
            assertEquals("FAILED", record[UPLOAD.PROCESSING_STATUS])
        }
    }

    @Test
    fun `markHaltedPendingAsFailed marks uploads with zero offset as failed`() {
        val submissionId = TestUtils.createMockSubmission(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId =
            dsl.transactionResult { tx ->
                tusUploadQueries.create(tx, submissionId, "never-started.txt", 100L)
            } ?: error("No uploadId")

        // upload_offset stays 0 (no chunks received), just set old updated_at
        dsl.execute(
            "UPDATE upload SET updated_at = NOW() - INTERVAL '5 minutes' WHERE id = ?",
            uploadId,
        )

        val cutoff = java.time.OffsetDateTime.now()
        val staleUploads =
            dsl.transactionResult { tx ->
                uploadRecoveryQueries.markHaltedPendingAsFailed(tx, cutoff)
            }

        assertEquals(listOf(submissionId), staleUploads.map { it.submissionId })
        dsl.transaction { tx ->
            val record =
                tx.dsl()
                    .select(UPLOAD.PROCESSING_STATUS)
                    .from(UPLOAD)
                    .where(UPLOAD.ID.eq(uploadId))
                    .fetchSingle()
            assertEquals("FAILED", record[UPLOAD.PROCESSING_STATUS])
        }
    }
}
