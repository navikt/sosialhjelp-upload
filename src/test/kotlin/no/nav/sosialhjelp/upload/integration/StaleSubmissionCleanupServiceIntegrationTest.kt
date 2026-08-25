package no.nav.sosialhjelp.upload.integration

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import no.nav.sosialhjelp.upload.action.fiks.MellomlagringClient
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.upload.StaleSubmissionCleanupService
import no.nav.sosialhjelp.upload.upload.StaleSubmissionQueries
import no.nav.sosialhjelp.upload.upload.SubmissionDeletionService
import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaleSubmissionCleanupServiceIntegrationTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private val staleSubmissionQueries = StaleSubmissionQueries()
    private val submissionQueries = SubmissionQueries(dsl)
    private val uploadRepository = UploadRepository()
    private lateinit var mellomlagringClient: MellomlagringClient
    private val chunkStorage = FileSystemStorage()

    @BeforeEach
    fun cleanDb() {
        dsl.deleteFrom(UPLOAD).execute()
        dsl.deleteFrom(SUBMISSION).execute()
        mellomlagringClient = mockk(relaxed = true)
    }

    private fun cleanupService(timeout: Duration = Duration.ofSeconds(1)) =
        StaleSubmissionCleanupService(
            dsl = dsl,
            submissionRetentionQueries = staleSubmissionQueries,
            submissionDeletionService =
                SubmissionDeletionService(
                    dsl = dsl,
                    submissionQueries = submissionQueries,
                    uploadRepository = uploadRepository,
                    mellomlagringClient = mellomlagringClient,
                    chunkStorage = chunkStorage,
                    notificationService = mockk(relaxed = true),
                ),
            meterRegistry = SimpleMeterRegistry(),
            idleTimeout = timeout,
        )

    private fun insertUpload(
        submissionId: UUID,
        status: String = "COMPLETE",
        updatedAt: OffsetDateTime = OffsetDateTime.now().minusSeconds(5),
        filId: UUID? = null,
    ): UUID {
        val uploadId = UUID.randomUUID()
        dsl
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, uploadId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, "test.pdf")
            .set(UPLOAD.PROCESSING_STATUS, status)
            .set(UPLOAD.UPDATED_AT, updatedAt)
            .set(UPLOAD.FIL_ID, filId)
            .execute()
        return uploadId
    }

    private fun submissionExists(submissionId: UUID) =
        dsl.selectFrom(SUBMISSION).where(SUBMISSION.ID.eq(submissionId)).fetchOne()

    @Test
    fun `stale submissions are deleted after retention period`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId =
            createMockSubmission(dsl, navEksternRefId = navEksternRefId, automaticCleanup = true)
        val filId = UUID.randomUUID()
        insertUpload(submissionId, filId = filId)

        runBlocking { cleanupService().runCleanup() }

        assertNull(submissionExists(submissionId), "Submission should be deleted after retention run")
        coVerify { mellomlagringClient.deleteFile(navEksternRefId, filId, any()) }
    }

    @Test
    fun `retention never deletes the whole mellomlagring for a navEksternRefId`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId =
            createMockSubmission(dsl, navEksternRefId = navEksternRefId, automaticCleanup = true)
        insertUpload(submissionId, filId = UUID.randomUUID())

        runBlocking { cleanupService().runCleanup() }

        coVerify(exactly = 0) { mellomlagringClient.deleteMellomlagring(any()) }
    }

    /**
     * Regression test for the production incident: a søknad has one submission per kategori, all
     * sharing a navEksternRefId. Cleaning up one of them must not touch the files belonging to a
     * sibling submission that is still alive — otherwise /vedlegg keeps listing files that Fiks no
     * longer has, and innsending fails with 400.
     */
    @Test
    fun `cleaning a stale submission does not touch files of a sibling submission on the same navEksternRefId`() {
        val navEksternRefId = UUID.randomUUID().toString()

        val staleSubmission =
            createMockSubmission(
                dsl,
                navEksternRefId = navEksternRefId,
                kategori = "husleie",
                automaticCleanup = true,
            )
        val staleFilId = UUID.randomUUID()
        insertUpload(staleSubmission, filId = staleFilId, updatedAt = OffsetDateTime.now().minusSeconds(5))

        val liveSubmission =
            createMockSubmission(
                dsl,
                navEksternRefId = navEksternRefId,
                kategori = "kontoutskrift",
                automaticCleanup = true,
            )
        val liveFilId = UUID.randomUUID()
        insertUpload(liveSubmission, filId = liveFilId, updatedAt = OffsetDateTime.now())

        runBlocking { cleanupService().runCleanup() }

        assertNull(submissionExists(staleSubmission), "Stale submission should be deleted")
        assertNotNull(submissionExists(liveSubmission), "Sibling submission should survive")

        coVerify(exactly = 0) { mellomlagringClient.deleteMellomlagring(any()) }
        coVerify(exactly = 1) { mellomlagringClient.deleteFile(navEksternRefId, staleFilId, any()) }
        coVerify(exactly = 0) { mellomlagringClient.deleteFile(navEksternRefId, liveFilId, any()) }
    }

    @Test
    fun `submissions without automatic cleanup are never deleted regardless of age`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId =
            createMockSubmission(
                dsl,
                navEksternRefId = navEksternRefId,
                kategori = "kontoutskrift",
                automaticCleanup = false,
            )
        insertUpload(submissionId, updatedAt = OffsetDateTime.now().minusDays(30), filId = UUID.randomUUID())

        runBlocking { cleanupService().runCleanup() }

        assertNotNull(
            submissionExists(submissionId),
            "Søknadsvedlegg must never be removed by retention — soknad-api owns that lifecycle",
        )
        coVerify(exactly = 0) { mellomlagringClient.deleteFile(any(), any(), any()) }
        coVerify(exactly = 0) { mellomlagringClient.deleteMellomlagring(any()) }
    }

    /**
     * A submission created by the SSE status route, where no upload has decided the policy yet,
     * must not be swept. Only an explicit opt-in makes a submission eligible.
     */
    @Test
    fun `submissions with an undecided cleanup policy are never deleted`() {
        val submissionId = createMockSubmission(dsl, automaticCleanup = null)
        insertUpload(submissionId, updatedAt = OffsetDateTime.now().minusDays(30), filId = UUID.randomUUID())

        runBlocking { cleanupService().runCleanup() }

        assertNotNull(submissionExists(submissionId), "An undecided submission must never be swept")
        coVerify(exactly = 0) { mellomlagringClient.deleteFile(any(), any(), any()) }
    }

    @Test
    fun `submissions not yet past retention period are kept`() {
        val submissionId = createMockSubmission(dsl, automaticCleanup = true)
        insertUpload(submissionId, updatedAt = OffsetDateTime.now())

        runBlocking { cleanupService(Duration.ofHours(1)).runCleanup() }

        assertNotNull(submissionExists(submissionId), "Submission should still exist when not past retention period")
    }

    @Test
    fun `pending uploads prevent submission from being cleaned up`() {
        val submissionId = createMockSubmission(dsl, automaticCleanup = true)
        insertUpload(submissionId, status = "PENDING")

        runBlocking { cleanupService().runCleanup() }

        assertNotNull(submissionExists(submissionId), "Submission with PENDING uploads should not be cleaned up")
    }

    @Test
    fun `deleting by navEksternRefId removes every submission and its files`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val a = createMockSubmission(dsl, navEksternRefId = navEksternRefId, kategori = "husleie")
        val b = createMockSubmission(dsl, navEksternRefId = navEksternRefId, kategori = "kontoutskrift")
        val filA = UUID.randomUUID()
        val filB = UUID.randomUUID()
        insertUpload(a, filId = filA)
        insertUpload(b, filId = filB)

        val deletionService =
            SubmissionDeletionService(
                dsl = dsl,
                submissionQueries = submissionQueries,
                uploadRepository = uploadRepository,
                mellomlagringClient = mellomlagringClient,
                chunkStorage = chunkStorage,
                notificationService = mockk(relaxed = true),
            )

        val deleted = runBlocking { deletionService.deleteByNavEksternRefId(navEksternRefId) }

        assertEquals(2, deleted)
        assertNull(submissionExists(a))
        assertNull(submissionExists(b))
        coVerify { mellomlagringClient.deleteFile(navEksternRefId, filA, any()) }
        coVerify { mellomlagringClient.deleteFile(navEksternRefId, filB, any()) }
    }

    @Test
    fun `deleting by kategori only removes that submission`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val husleie = createMockSubmission(dsl, navEksternRefId = navEksternRefId, kategori = "husleie")
        val konto = createMockSubmission(dsl, navEksternRefId = navEksternRefId, kategori = "kontoutskrift")
        val husleieFil = UUID.randomUUID()
        val kontoFil = UUID.randomUUID()
        insertUpload(husleie, filId = husleieFil)
        insertUpload(konto, filId = kontoFil)

        val deletionService =
            SubmissionDeletionService(
                dsl = dsl,
                submissionQueries = submissionQueries,
                uploadRepository = uploadRepository,
                mellomlagringClient = mellomlagringClient,
                chunkStorage = chunkStorage,
                notificationService = mockk(relaxed = true),
            )

        val deleted = runBlocking { deletionService.deleteByNavEksternRefId(navEksternRefId, "husleie") }

        assertEquals(1, deleted)
        assertNull(submissionExists(husleie))
        assertNotNull(submissionExists(konto), "Files for other kategorier must survive")
        coVerify(exactly = 1) { mellomlagringClient.deleteFile(navEksternRefId, husleieFil, any()) }
        coVerify(exactly = 0) { mellomlagringClient.deleteFile(navEksternRefId, kontoFil, any()) }
    }
}
