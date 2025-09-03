package no.nav.sosialhjelp.status

import no.nav.sosialhjelp.database.DocumentRepository
import kotlinx.coroutines.*
import no.nav.sosialhjelp.common.TestUtils.createMockDocument
import no.nav.sosialhjelp.database.schema.PageTable
import no.nav.sosialhjelp.database.schema.UploadTable
import no.nav.sosialhjelp.status.dto.DocumentState
import no.nav.sosialhjelp.status.dto.PageState
import no.nav.sosialhjelp.status.dto.UploadSuccessState
import no.nav.sosialhjelp.testutils.PostgresTestContainer
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DocumentStatusServiceTest {
    val documentRepository = DocumentRepository()

    companion object {
        @BeforeAll
        @JvmStatic
        fun setup() {
            PostgresTestContainer.connectAndStart()
        }
    }

    @BeforeEach
    fun cleanupBeforeEach() {
        transaction {
            PageTable.deleteAll()
            UploadTable.deleteAll()
        }
    }

    /**
     * Test that when no uploads exist for a given document, the service returns a DocumentState
     * with an empty uploads map.
     */
    @Test
    fun `getDocumentStatus returns empty state when no uploads exist`() =
        runBlocking {
            val documentId = createMockDocument(documentRepository)
            val service = DocumentStatusService()

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
        runBlocking {
            // Given
            val documentId = createMockDocument(documentRepository)
            // Insert one upload row associated with the document.
            val uploadId =
                transaction {
                    UploadTable.insert {
                        it[document] = documentId
                        it[originalFilename] = "file.pdf"
                    } get UploadTable.id
                }
            val service = DocumentStatusService()

            // When
            val result: DocumentState = service.getDocumentStatus(documentId)

            // Then
            assertEquals(documentId.toString(), result.documentId)
            assertEquals(1, result.uploads.size)
            val uploadState: UploadSuccessState? = result.uploads[uploadId.toString()]
            assertNotNull(uploadState)
            assertEquals("file.pdf", uploadState.originalFilename)
            assertTrue(uploadState.pages!!.isEmpty())
        }

    /**
     * Test that when a single upload has pages inserted, the returned state includes the upload
     * with the corresponding list of page states.
     */
    @Test
    fun `getDocumentStatus returns upload with pages when pages exist`() =
        runBlocking {
            // Given
            val documentId = createMockDocument(documentRepository)
            val uploadId =
                transaction {
                    UploadTable.insert {
                        it[document] = documentId
                        it[originalFilename] = "document.pdf"
                    } get UploadTable.id
                }
            // Insert two pages for the above upload.
            transaction {
                PageTable.insert {
                    it[upload] = uploadId
                    it[pageNumber] = 1
                    it[filename] = "page1.pdf"
                }
                PageTable.insert {
                    it[upload] = uploadId
                    it[pageNumber] = 2
                    it[filename] = "page2.pdf"
                }
            }
            val service = DocumentStatusService()

            // When
            val result: DocumentState = service.getDocumentStatus(documentId)

            // Then
            assertEquals(documentId.toString(), result.documentId)
            assertEquals(1, result.uploads.size)
            val uploadState: UploadSuccessState? = result.uploads[uploadId.toString()]
            assertNotNull(uploadState)
            assertEquals("document.pdf", uploadState.originalFilename)
            // Verify that both pages are returned with correct details.
            assertEquals(2, uploadState.pages!!.size)
            val page1: PageState? = uploadState.pages.find { it.pageNumber == 1 }
            val page2: PageState? = uploadState.pages.find { it.pageNumber == 2 }
            assertNotNull(page1)
            assertNotNull(page2)
            assertEquals("page1.pdf", page1.thumbnail)
            assertEquals("page2.pdf", page2.thumbnail)
        }

    /**
     * Test that multiple uploads (each with their own pages) for the same document are all
     * correctly returned in the DocumentState.
     */
    @Test
    fun `getDocumentStatus returns multiple uploads each with their corresponding pages`() =
        runBlocking {
            // Given
            val documentId = createMockDocument(documentRepository)

            val uploadId1 =
                transaction {
                    UploadTable.insert {
                        it[document] = documentId
                        it[originalFilename] = "first.pdf"
                    } get UploadTable.id
                }
            val uploadId2 =
                transaction {
                    UploadTable.insert {
                        it[document] = documentId
                        it[originalFilename] = "second.pdf"
                    } get UploadTable.id
                }
            // Insert pages for the first upload.
            transaction {
                PageTable.insert {
                    it[upload] = uploadId1
                    it[pageNumber] = 1
                    it[filename] = "first_page1.pdf"
                }
            }
            // Insert pages for the second upload.
            transaction {
                PageTable.insert {
                    it[upload] = uploadId2
                    it[pageNumber] = 1
                    it[filename] = "second_page1.pdf"
                }
                PageTable.insert {
                    it[upload] = uploadId2
                    it[pageNumber] = 2
                    it[filename] = "second_page2.pdf"
                }
            }
            val service = DocumentStatusService()

            // When
            val result: DocumentState = service.getDocumentStatus(documentId)

            // Then
            assertEquals(documentId.toString(), result.documentId)
            assertEquals(2, result.uploads.size)
            val firstUpload: UploadSuccessState? = result.uploads[uploadId1.toString()]
            val secondUpload: UploadSuccessState? = result.uploads[uploadId2.toString()]
            assertNotNull(firstUpload)
            assertNotNull(secondUpload)
            assertEquals("first.pdf", firstUpload.originalFilename)
            assertEquals("second.pdf", secondUpload.originalFilename)
            assertEquals(1, firstUpload.pages!!.size)
            assertEquals(2, secondUpload.pages!!.size)
        }
}
