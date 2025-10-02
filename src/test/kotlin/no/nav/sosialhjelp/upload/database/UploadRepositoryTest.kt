package no.nav.sosialhjelp.upload.database

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.sosialhjelp.upload.common.TestUtils
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import no.nav.sosialhjelp.upload.database.notify.DocumentNotificationService
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRepositoryTest {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var uploadRepository: UploadRepository
    private val dsl: DSLContext = PostgresTestContainer.dsl
    private lateinit var notificationServiceMock: DocumentNotificationService

    @BeforeAll
    fun setupDatabase() {
        PostgresTestContainer.migrate()
        documentRepository = DocumentRepository(dsl)
        notificationServiceMock = mockk<DocumentNotificationService>()
        uploadRepository = UploadRepository(notificationServiceMock)
    }

    @BeforeEach
    fun cleanup() {
        dsl.transaction { it ->
            it.dsl().deleteFrom(DOCUMENT).execute()
        }
    }

    @Test
    fun `test create upload`() {
        val documentId = TestUtils.createMockDocument(dsl)
        val filename = "testfile.txt"
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId = dsl.transactionResult { tx -> uploadRepository.create(tx, documentId, filename) }

        dsl.transaction { tx ->
            val upload =
                tx
                    .dsl()
                    .select(
                        UPLOAD.DOCUMENT_ID,
                        UPLOAD.ORIGINAL_FILENAME,
                    ).from(UPLOAD)
                    .where(UPLOAD.ID.eq(uploadId!!))
                    .fetchSingle()
            assertEquals(documentId, upload[UPLOAD.DOCUMENT_ID])
            assertEquals(filename, upload[UPLOAD.ORIGINAL_FILENAME])
        }
    }

    @Test
    fun `test getUploadsByDocumentId returns correct uploads`() {
        val documentId = TestUtils.createMockDocument(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId1 = dsl.transactionResult { it -> uploadRepository.create(it, documentId, "file1.txt") }
        val uploadId2 = dsl.transactionResult { it -> uploadRepository.create(it, documentId, "file2.txt") }
        // Create an upload for a different document to ensure filtering works.
        val otherDocumentId = TestUtils.createMockDocument(dsl)
        dsl.transaction { it -> uploadRepository.create(it, otherDocumentId, "otherfile.txt") }

        val uploads =
            dsl
                .transactionResult { tx ->
                    uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
                }.map { it.id }
        assertTrue(uploads.contains(uploadId1))
        assertTrue(uploads.contains(uploadId2))
        assertEquals(2, uploads.size)
    }

    @Test
    fun `test getDocumentIdFromUploadId returns correct document id`() {
        val documentId = TestUtils.createMockDocument(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId = dsl.transactionResult { it -> uploadRepository.create(it, documentId, "file.txt") } ?: error("Ingen uploadId")
        // Retrieve the document id using the upload id.
        val retrievedDocumentId = dsl.transactionResult { it -> uploadRepository.getDocumentIdFromUploadId(it, uploadId) }
        assertEquals(documentId, retrievedDocumentId)
    }

    @Test
    fun `test notifyChange calls DocumentRepository with correct document id`() {
        val documentId = TestUtils.createMockDocument(dsl)
        every { notificationServiceMock.notifyUpdate(any()) } returns Unit
        val uploadId = dsl.transactionResult { it -> uploadRepository.create(it, documentId, "notify.txt") } ?: error("Ingen uploadId")

        dsl.transaction { it -> uploadRepository.notifyChange(it, uploadId) }

        verify { notificationServiceMock.notifyUpdate(documentId) }
    }
}
