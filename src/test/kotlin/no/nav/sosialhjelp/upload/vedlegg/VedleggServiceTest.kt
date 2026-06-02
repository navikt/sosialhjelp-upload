package no.nav.sosialhjelp.upload.vedlegg

import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
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
class VedleggServiceTest {
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var uploadRepository: UploadRepository
    private lateinit var service: VedleggService

    @BeforeAll
    fun setup() {
        PostgresTestContainer.migrate()
        uploadRepository = UploadRepository()
        service = VedleggService(dsl, uploadRepository)
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
        kategori: String? = null,
    ): UUID {
        val uploadId = UUID.randomUUID()
        dsl.transaction { config ->
            config.dsl()
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, uploadId)
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, filename)
                .set(UPLOAD.MELLOMLAGRING_FILNAVN, mellomlagringFilnavn)
                .set(UPLOAD.SHA512, sha512)
                .set(UPLOAD.PROCESSING_STATUS, "COMPLETE")
                .set(UPLOAD.KATEGORI, kategori)
                .execute()
        }
        return uploadId
    }

    @Test
    fun `getVedleggByNavEksternRefId returns empty list when no uploads exist`() {
        val navEksternRefId = UUID.randomUUID().toString()
        createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)

        assertTrue(result.vedlegg.isEmpty())
    }

    @Test
    fun `getVedleggByNavEksternRefId defaults null document type to annet`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)
        insertCompleteUpload(submissionId, "test.pdf", "mellom.pdf", kategori = null)

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)

        assertEquals(1, result.vedlegg.size)
        assertEquals("annet", result.vedlegg[0].kategori)
    }

    @Test
    fun `getVedleggByNavEksternRefId groups uploads with same kategori`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)
        insertCompleteUpload(submissionId, "a.pdf", "mella.pdf", kategori = "lonnslipp")
        insertCompleteUpload(submissionId, "b.pdf", "mellb.pdf", kategori = "lonnslipp")
        insertCompleteUpload(submissionId, "c.pdf", "mellc.pdf", kategori = "annet")

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)

        assertEquals(2, result.vedlegg.size)
        val lonnslipp = result.vedlegg.find { it.kategori == "lonnslipp" }
        assertNotNull(lonnslipp)
        assertEquals(2, lonnslipp.filer.size)
    }

    @Test
    fun `setDocumentType updates kategori on upload`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)
        val uploadId = insertCompleteUpload(submissionId, "a.pdf", "mella.pdf")

        service.setDocumentCategory(uploadId, "lonnslipp")

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)
        val vedlegg = result.vedlegg.single()
        assertEquals("lonnslipp", vedlegg.kategori)
    }

    @Test
    fun `non-COMPLETE uploads are excluded from results`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        // Insert a PENDING upload — should be excluded
        val uploadId = UUID.randomUUID()
        dsl.transaction { config ->
            config.dsl()
                .insertInto(UPLOAD)
                .set(UPLOAD.ID, uploadId)
                .set(UPLOAD.SUBMISSION_ID, submissionId)
                .set(UPLOAD.ORIGINAL_FILENAME, "pending.pdf")
                .set(UPLOAD.PROCESSING_STATUS, "PENDING")
                .execute()
        }

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)
        assertTrue(result.vedlegg.isEmpty())
    }
}
