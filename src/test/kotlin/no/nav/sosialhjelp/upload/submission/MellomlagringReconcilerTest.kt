package no.nav.sosialhjelp.upload.submission

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.submission.MellomlagringReconciler.Result
import no.nav.sosialhjelp.upload.testutils.InMemoryMellomlagring
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.upload.UploadRepository
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MellomlagringReconcilerTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var meters: SimpleMeterRegistry
    private lateinit var mellomlagring: InMemoryMellomlagring
    private lateinit var reconciler: MellomlagringReconciler
    private val ref = "ref-1"

    @BeforeAll
    fun migrate() {
        PostgresTestContainer.migrate()
    }

    @BeforeEach
    fun setup() {
        dsl.transaction { tx ->
            tx.dsl().deleteFrom(UPLOAD).execute()
            tx.dsl().deleteFrom(SUBMISSION).execute()
        }
        meters = SimpleMeterRegistry()
        mellomlagring = InMemoryMellomlagring()
        reconciler = MellomlagringReconciler(mellomlagring.client, UploadRepository(), dsl, meters)
    }

    private fun insertUpload(
        submissionId: UUID,
        filId: UUID?,
        status: String = "COMPLETE",
        filename: String = "a.pdf",
    ): UUID {
        val id = UUID.randomUUID()
        dsl.transaction { tx ->
            tx
                .dsl()
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, id)
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, filename)
                .set(UPLOAD.FIL_ID, filId)
                .set(UPLOAD.MELLOMLAGRING_FILNAVN, "$filename-abcd1234")
                .set(UPLOAD.PROCESSING_STATUS, status)
                .execute()
        }
        return id
    }

    private fun uploadIds(): Set<UUID> =
        dsl
            .select(UPLOAD.ID)
            .from(UPLOAD)
            .fetch(UPLOAD.ID)
            .filterNotNull()
            .toSet()

    private fun count(name: String) = meters.counter(name).count()

    @Test
    fun `clean when rows and files match`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            val filId = mellomlagring.put(ref, "a-abcd1234.pdf")
            insertUpload(sub, filId)

            assertEquals(Result.Clean, reconciler.reconcile(ref))
            assertEquals(1, mellomlagring.files(ref).size)
        }

    @Test
    fun `deletes orphan file without a row`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            insertUpload(sub, mellomlagring.put(ref, "a-abcd1234.pdf"))
            mellomlagring.put(ref, "stray-ffff0000.pdf")

            assertEquals(Result.Clean, reconciler.reconcile(ref))
            assertEquals(listOf("a-abcd1234.pdf"), mellomlagring.files(ref).map { it.filnavn })
            assertEquals(1.0, count("mellomlagring.reconcile.orphan_deleted"))
        }

    @Test
    fun `deletes orphan with unexpected name`() =
        runTest {
            createMockSubmission(dsl, navEksternRefId = ref)
            mellomlagring.put(ref, "whatever.bin")

            assertEquals(Result.Clean, reconciler.reconcile(ref))
            assertTrue(mellomlagring.files(ref).isEmpty())
        }

    @Test
    fun `leaves ettersendelse pdf alone`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            insertUpload(sub, mellomlagring.put(ref, "a-abcd1234.pdf"))
            mellomlagring.put(ref, "ettersendelse.pdf")

            assertEquals(Result.Clean, reconciler.reconcile(ref))
            assertEquals(2, mellomlagring.files(ref).size)
        }

    @Test
    fun `missing file deletes the row and reports it`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            val kept = insertUpload(sub, mellomlagring.put(ref, "a-abcd1234.pdf"))
            val lost = insertUpload(sub, UUID.randomUUID(), filename = "lost.pdf")

            val result = reconciler.reconcile(ref)

            assertIs<Result.Missing>(result)
            assertEquals(listOf(lost), result.uploads.map { it.uploadId })
            assertEquals("lost.pdf", result.uploads.single().originalFilename)
            assertEquals(setOf(kept), uploadIds())
            assertEquals(1.0, count("mellomlagring.reconcile.missing"))
        }

    @Test
    fun `in progress touches nothing`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            val pending = insertUpload(sub, null, status = "PENDING")
            val lost = insertUpload(sub, UUID.randomUUID())
            mellomlagring.put(ref, "stray-ffff0000.pdf")

            assertEquals(Result.InProgress, reconciler.reconcile(ref))
            assertEquals(1, mellomlagring.files(ref).size)
            assertEquals(setOf(pending, lost), uploadIds())
        }

    @Test
    fun `failed rows are ignored`() =
        runTest {
            val sub = createMockSubmission(dsl, navEksternRefId = ref)
            insertUpload(sub, null, status = "FAILED")

            assertEquals(Result.Clean, reconciler.reconcile(ref))
        }

    @Test
    fun `covers sibling submissions under one navEksternRefId`() =
        runTest {
            val a = createMockSubmission(dsl, navEksternRefId = ref, kategori = "a")
            val b = createMockSubmission(dsl, navEksternRefId = ref, kategori = "b")
            insertUpload(a, mellomlagring.put(ref, "a-abcd1234.pdf"))
            insertUpload(b, mellomlagring.put(ref, "b-abcd1234.pdf"))

            assertEquals(Result.Clean, reconciler.reconcile(ref))
            assertEquals(2, mellomlagring.files(ref).size)
        }

    @Test
    fun `failed orphan delete throws`() =
        runTest {
            createMockSubmission(dsl, navEksternRefId = ref)
            mellomlagring.put(ref, "stray-ffff0000.pdf")
            mellomlagring.failDeletes = true

            assertFailsWith<IllegalStateException> { reconciler.reconcile(ref) }
        }
}
