package no.nav.sosialhjelp.upload.vedlegg

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.SubmissionQueries
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.submission.MellomlagringReconciler
import no.nav.sosialhjelp.upload.testutils.InMemoryMellomlagring
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.tus.storage.FileSystemStorage
import no.nav.sosialhjelp.upload.upload.SubmissionDeletionService
import no.nav.sosialhjelp.upload.upload.UploadRepository
import no.nav.sosialhjelp.upload.validation.SubmissionFilesMissingException
import no.nav.sosialhjelp.upload.validation.SubmissionUploadsInProgressException
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VedleggServiceTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var uploadRepository: UploadRepository
    private lateinit var service: VedleggService
    private lateinit var mellomlagring: InMemoryMellomlagring

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        uploadRepository = UploadRepository()
        mellomlagring = InMemoryMellomlagring()
        service =
            VedleggService(
                dsl,
                uploadRepository,
                mockk(relaxed = true),
                MellomlagringReconciler(mellomlagring.client, uploadRepository, dsl, SimpleMeterRegistry()),
                PostgresTestContainer.lock,
            )
    }

    @BeforeEach
    fun cleanup() {
        dsl.transaction { config ->
            config.dsl().deleteFrom(UPLOAD).execute()
            config.dsl().deleteFrom(SUBMISSION).execute()
        }
    }

    private fun insertCompleteUpload(
        submissionId: UUID,
        filename: String,
        mellomlagringFilnavn: String,
        sha512: String = "abc123",
    ): UUID {
        val uploadId = UUID.randomUUID()
        val navEksternRefId =
            dsl
                .select(SUBMISSION.NAV_EKSTERN_REF_ID)
                .from(SUBMISSION)
                .where(SUBMISSION.ID.eq(submissionId))
                .fetchSingle(SUBMISSION.NAV_EKSTERN_REF_ID)!!
        val filId = mellomlagring.put(navEksternRefId, mellomlagringFilnavn)
        dsl.transaction { config ->
            config
                .dsl()
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, uploadId)
                .set(UPLOAD.FIL_ID, filId)
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, filename)
                .set(UPLOAD.MELLOMLAGRING_FILNAVN, mellomlagringFilnavn)
                .set(UPLOAD.SHA512, sha512)
                .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
                .execute()
        }
        return uploadId
    }

    private fun createMockSubmissionWithKategori(
        navEksternRefId: String,
        kategori: String? = null,
    ): UUID {
        val uuid = UUID.randomUUID()
        dsl.transaction { config ->
            config
                .dsl()
                .insertInto(SUBMISSION)
                .set(SUBMISSION.ID, uuid)
                .set(SUBMISSION.OWNER_IDENT, "12345678910")
                .set(SUBMISSION.CONTEXT_ID, UUID.randomUUID().toString())
                .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
                .set(SUBMISSION.KATEGORI, kategori)
                .execute()
        }
        return uuid
    }

    @Test
    fun `getVedleggByNavEksternRefId returns empty list when no uploads exist`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            createMockSubmission(dsl, navEksternRefId = navEksternRefId)

            val result = service.getVedleggByNavEksternRefId(navEksternRefId)

            assertTrue(result.vedlegg.isEmpty())
        }

    @Test
    fun `getVedleggByNavEksternRefId groups uploads with same kategori`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmissionWithKategori(navEksternRefId, kategori = "lonnslipp")
            insertCompleteUpload(submissionId, "a.pdf", "mella.pdf")
            insertCompleteUpload(submissionId, "b.pdf", "mellb.pdf")

            val result = service.getVedleggByNavEksternRefId(navEksternRefId)

            assertEquals(1, result.vedlegg.size)
            val vedlegg = result.vedlegg.single()
            assertEquals("lonnslipp", vedlegg.kategori)
            assertEquals(2, vedlegg.filer.size)
        }

    @Test
    fun `getVedleggByNavEksternRefId splits uploads with different kategori into separate vedlegg`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionA = createMockSubmissionWithKategori(navEksternRefId + "-a", kategori = "lonnslipp")
            val submissionB = createMockSubmissionWithKategori(navEksternRefId + "-b", kategori = "annet")

            dsl.transaction { config ->
                config
                    .dsl()
                    .update(SUBMISSION)
                    .set(SUBMISSION.NAV_EKSTERN_REF_ID, navEksternRefId)
                    .where(SUBMISSION.ID.`in`(submissionA, submissionB))
                    .execute()
            }

            insertCompleteUpload(submissionA, "a.pdf", "mella.pdf")
            insertCompleteUpload(submissionB, "c.pdf", "mellc.pdf")

            val result = service.getVedleggByNavEksternRefId(navEksternRefId)

            assertEquals(2, result.vedlegg.size)
            assertNotNull(result.vedlegg.find { it.kategori == "lonnslipp" })
            assertNotNull(result.vedlegg.find { it.kategori == "annet" })
        }

    @Test
    fun `non-COMPLETE uploads are excluded from results`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

            val uploadId = UUID.randomUUID()
            dsl.transaction { config ->
                config
                    .dsl()
                    .insertInto(UPLOAD)
                    .set(UPLOAD.ID, uploadId)
                    .set(UPLOAD.SUBMISSION_ID, submissionId)
                    .set(UPLOAD.ORIGINAL_FILENAME, "pending.pdf")
                    .set(UPLOAD.PROCESSING_STATUS, "FAILED")
                    .execute()
            }

            val result = service.getVedleggByNavEksternRefId(navEksternRefId)
            assertTrue(result.vedlegg.isEmpty())
        }

    @Test
    fun `getVedleggByNavEksternRefId throws and deletes row when file is missing`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmissionWithKategori(navEksternRefId, kategori = "lonnslipp")
            insertCompleteUpload(submissionId, "a.pdf", "mella.pdf")
            mellomlagring.files(navEksternRefId).forEach {
                mellomlagring.client.deleteFile(navEksternRefId, UUID.fromString(it.filId))
            }

            val ex =
                assertFailsWith<SubmissionFilesMissingException> {
                    service.getVedleggByNavEksternRefId(
                        navEksternRefId,
                    )
                }

            assertEquals(listOf("a.pdf"), ex.missing.map { it.originalFilename })
            assertEquals(0, dsl.fetchCount(UPLOAD))
        }

    @Test
    fun `getVedleggByNavEksternRefId throws when uploads are in progress`() =
        runTest {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmissionWithKategori(navEksternRefId)
            dsl
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, UUID.randomUUID())
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, "p.pdf")
                .set(UPLOAD.PROCESSING_STATUS, "PENDING")
                .execute()

            assertFailsWith<SubmissionUploadsInProgressException> {
                service.getVedleggByNavEksternRefId(
                    navEksternRefId,
                )
            }
        }

    @Test
    fun `upload holding the shared lock is never reconciled as an orphan`() =
        runBlocking {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmissionWithKategori(navEksternRefId, kategori = "annet")
            val stored = CompletableDeferred<Unit>()
            val insertRow = CompletableDeferred<Unit>()

            // Mirrors FileUploadService: shared lock from storing the file until the row commits.
            val upload =
                launch(Dispatchers.IO) {
                    PostgresTestContainer.lock.shared(navEksternRefId) {
                        val filId = mellomlagring.put(navEksternRefId, "live.pdf")
                        stored.complete(Unit)
                        insertRow.await()
                        insertRow(submissionId, filId, "live.pdf")
                    }
                }
            stored.await()

            val spec = async(Dispatchers.IO) { service.getVedleggByNavEksternRefId(navEksternRefId) }
            delay(300)
            assertTrue(spec.isActive, "reconcile must wait for the upload to commit its row")

            insertRow.complete(Unit)
            upload.join()

            val result = spec.await()
            assertEquals(listOf("live.pdf"), result.vedlegg.flatMap { v -> v.filer.map { it.filnavn } })
            assertEquals(1, mellomlagring.files(navEksternRefId).size)
        }

    @Test
    fun `delete holding the shared lock never produces a false missing`() =
        runBlocking {
            val navEksternRefId = UUID.randomUUID().toString()
            val submissionId = createMockSubmissionWithKategori(navEksternRefId, kategori = "annet")
            val uploadId = insertCompleteUpload(submissionId, "gone.pdf", "gone.pdf")
            val rowDeleted = CompletableDeferred<Unit>()
            val deleteFile = CompletableDeferred<Unit>()

            // Mirrors FileUploadService.delete: row, then file, under the shared lock.
            val delete =
                launch(Dispatchers.IO) {
                    PostgresTestContainer.lock.shared(navEksternRefId) {
                        dsl.deleteFrom(UPLOAD).where(UPLOAD.ID.eq(uploadId)).execute()
                        rowDeleted.complete(Unit)
                        deleteFile.await()
                        mellomlagring.files(navEksternRefId).forEach {
                            mellomlagring.client.deleteFile(navEksternRefId, UUID.fromString(it.filId), true)
                        }
                    }
                }
            rowDeleted.await()

            val spec = async(Dispatchers.IO) { service.getVedleggByNavEksternRefId(navEksternRefId) }
            delay(300)
            assertTrue(spec.isActive)

            deleteFile.complete(Unit)
            delete.join()

            assertTrue(spec.await().vedlegg.isEmpty())
        }

    @Test
    fun `submission deletion waits for exclusive lock before removing rows and files`() =
        runBlocking {
            val navEksternRefId = UUID.randomUUID().toString()
            val husleie = createMockSubmissionWithKategori(navEksternRefId, kategori = "husleie")
            insertCompleteUpload(husleie, "husleie.pdf", "husleie.pdf")

            val deletionService =
                SubmissionDeletionService(
                    dsl = dsl,
                    submissionQueries = SubmissionQueries(dsl),
                    uploadRepository = uploadRepository,
                    mellomlagringClient = mellomlagring.client,
                    chunkStorage = FileSystemStorage(),
                    notificationService = mockk(relaxed = true),
                    meterRegistry = SimpleMeterRegistry(),
                    lock = PostgresTestContainer.lock,
                )

            val locked = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder =
                launch(Dispatchers.IO) {
                    PostgresTestContainer.lock.exclusive(navEksternRefId) {
                        locked.complete(Unit)
                        release.await()
                    }
                }
            locked.await()

            val deleteJob =
                async(Dispatchers.IO) {
                    deletionService.deleteByNavEksternRefId(
                        navEksternRefId,
                        keepMellomlagring = false,
                        kategori = "husleie",
                    )
                }
            delay(300)
            assertTrue(deleteJob.isActive, "submission delete must wait for exclusive lock")
            assertEquals(1, dsl.fetchCount(UPLOAD))
            assertEquals(1, mellomlagring.files(navEksternRefId).size)

            release.complete(Unit)
            holder.join()

            assertEquals(1, deleteJob.await())
            assertEquals(0, dsl.fetchCount(UPLOAD))
            assertTrue(mellomlagring.files(navEksternRefId).isEmpty())
        }

    private fun insertRow(
        submissionId: UUID,
        filId: UUID,
        filnavn: String,
    ) {
        dsl
            .insertInto(UPLOAD)
            .set(UPLOAD.ID, UUID.randomUUID())
            .set(UPLOAD.FIL_ID, filId)
            .set(UPLOAD.SUBMISSION_ID, submissionId)
            .set(UPLOAD.ORIGINAL_FILENAME, filnavn)
            .set(UPLOAD.MELLOMLAGRING_FILNAVN, filnavn)
            .set(UPLOAD.SHA512, "abc")
            .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
            .execute()
    }
}
