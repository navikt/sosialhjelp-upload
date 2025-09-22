package no.nav.sosialhjelp.upload.status

import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.common.TestUtils.createMockDocument
import no.nav.sosialhjelp.upload.database.DocumentRepository
import no.nav.sosialhjelp.upload.database.PageRepository
import no.nav.sosialhjelp.upload.database.UploadRepository
import no.nav.sosialhjelp.upload.database.generated.tables.Page.Companion.PAGE
import no.nav.sosialhjelp.upload.database.generated.tables.references.UPLOAD
import no.nav.sosialhjelp.upload.status.dto.DocumentState
import no.nav.sosialhjelp.upload.status.dto.UploadSuccessState
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
class DocumentStatusServiceTest {
    private lateinit var documentRepository: DocumentRepository
    private lateinit var uploadRepository: UploadRepository
    private lateinit var pageRepository: PageRepository
    private lateinit var dsl: DSLContext

    @BeforeAll
    fun setup() {
        dsl = PostgresTestContainer.connectAndStart()
        documentRepository = DocumentRepository(dsl)
        uploadRepository = UploadRepository()
        pageRepository = PageRepository()
    }

    @BeforeEach
    fun cleanupBeforeEach() =
        dsl.transaction { config ->
            config.dsl().deleteFrom(PAGE).execute()
            config.dsl().deleteFrom(UPLOAD).execute()
        }

    /**
     * Test that when no uploads exist for a given document, the service returns a DocumentState
     * with an empty uploads map.
     */
    @Test
    fun `getDocumentStatus returns empty state when no uploads exist`() {
        val documentId = createMockDocument(dsl)
        val service = DocumentStatusService(uploadRepository, pageRepository, dsl)

        // When: retrieving document status
        val result: DocumentState = service.getDocumentStatus(documentId)

        // Then: the returned state should have the matching documentId and no uploads.
        assertEquals(documentId.toString(), result.documentId)
        assertTrue(result.uploads.isEmpty())
    }

    /**
     * Test that when one upload exists (with no pages), the returned DocumentState correctly
     * contains that upload with an empty page list.
     */
    @Test
    fun `getDocumentStatus returns upload with empty pages list when upload exists but no pages`() =
        runTest {
            // Given
            val documentId = createMockDocument(dsl)
            // Insert one upload row associated with the document.
            val uploadId =
                dsl.transaction { it ->
                    it
                        .dsl()
                        .insertInto(
                            UPLOAD,
                        ).set(
                            UPLOAD.ID,
                            UUID.randomUUID(),
                        ).set(UPLOAD.DOCUMENT_ID, documentId)
                        .set(UPLOAD.ORIGINAL_FILENAME, "file.pdf")
                        .returning(UPLOAD.ID)
                        .fetchSingle()[UPLOAD.ID]
                }
            val service = DocumentStatusService(uploadRepository, pageRepository, dsl)

            // When
            val result: DocumentState = service.getDocumentStatus(documentId)

            // Then
            assertEquals(documentId.toString(), result.documentId)
            assertEquals(1, result.uploads.size)
            val uploadState: UploadSuccessState? = result.uploads.find { it.id == uploadId }
            assertNotNull(uploadState)
            assertEquals("file.pdf", uploadState.originalFilename)
            assertTrue(uploadState.pages!!.isEmpty())
        }

    private fun createUpload(
        documentId: UUID,
        filename: String,
    ) = dsl.transaction { it ->
        it
            .dsl()
            .insertInto(
                UPLOAD,
            ).set(
                UPLOAD.ID,
                UUID.randomUUID(),
            ).set(UPLOAD.DOCUMENT_ID, documentId)
            .set(UPLOAD.ORIGINAL_FILENAME, filename)
            .returning(UPLOAD.ID)
            .fetchSingle()[UPLOAD.ID]
    }

    /**
     * Test that multiple uploads for the same document are all
     * correctly returned in the DocumentState.
     */
    @Test
    fun `getDocumentStatus returns multiple uploads each with their corresponding pages`() =
        runTest {
            // Given
            val documentId = createMockDocument(dsl)

            val uploadId1 =
                createUpload(documentId, "first.pdf")
            val uploadId2 =
                createUpload(documentId, "second.pdf")

            val service = DocumentStatusService(uploadRepository, pageRepository, dsl)

            // When
            val result: DocumentState = service.getDocumentStatus(documentId)

            // Then
            assertEquals(documentId.toString(), result.documentId)
            assertEquals(2, result.uploads.size)
            val firstUpload: UploadSuccessState? = result.uploads.find { it.id == uploadId1 }
            val secondUpload: UploadSuccessState? = result.uploads.find { it.id == uploadId2 }
            assertNotNull(firstUpload)
            assertNotNull(secondUpload)
            assertEquals("first.pdf", firstUpload.originalFilename)
            assertEquals("second.pdf", secondUpload.originalFilename)
        }
}
