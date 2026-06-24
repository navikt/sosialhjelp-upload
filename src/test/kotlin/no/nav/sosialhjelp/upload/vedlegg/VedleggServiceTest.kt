package no.nav.sosialhjelp.upload.vedlegg

import no.nav.sosialhjelp.upload.common.TestUtils.createMockSubmission
import no.nav.sosialhjelp.upload.database.generated.tables.references.SUBMISSION
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import no.nav.sosialhjelp.upload.upload.UploadRepository
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
            config.dsl()
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
    fun `getVedleggByNavEksternRefId returns empty list when no uploads exist`() {
        val navEksternRefId = UUID.randomUUID().toString()
        createMockSubmission(dsl, navEksternRefId = navEksternRefId)

        val result = service.getVedleggByNavEksternRefId(navEksternRefId)

        assertTrue(result.vedlegg.isEmpty())
    }

    @Test
    fun `getVedleggByNavEksternRefId groups uploads with same kategori`() {
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
    fun `getVedleggByNavEksternRefId splits uploads with different kategori into separate vedlegg`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionA = createMockSubmissionWithKategori(navEksternRefId + "-a", kategori = "lonnslipp")
        val submissionB = createMockSubmissionWithKategori(navEksternRefId + "-b", kategori = "annet")

        dsl.transaction { config ->
            config.dsl().update(SUBMISSION)
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
    fun `non-COMPLETE uploads are excluded from results`() {
        val navEksternRefId = UUID.randomUUID().toString()
        val submissionId = createMockSubmission(dsl, navEksternRefId = navEksternRefId)

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
