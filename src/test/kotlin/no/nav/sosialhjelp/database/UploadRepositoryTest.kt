package no.nav.sosialhjelp.database

import DocumentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import no.nav.sosialhjelp.common.TestUtils.createMockDocument
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.database.schema.UploadTable
import no.nav.sosialhjelp.testutils.PostgresTestContainer
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRepositoryTest {
    private val documentRepository = DocumentRepository()
    private val uploadRepository = UploadRepository()

    @BeforeAll
    fun setupDatabase() {
        PostgresTestContainer.connectAndStart()
    }

    @BeforeEach
    fun cleanup() {
        transaction {
            UploadTable.deleteAll()
            DocumentTable.deleteAll()
        }
    }

    @Test
    fun `test create upload`() {
        val documentId = createMockDocument(documentRepository)
        val filename = "testfile.txt"
        val uploadId = transaction { uploadRepository.create(documentId, filename) }

        transaction {
            val row = UploadTable.selectAll().where { UploadTable.id eq uploadId }.single()
            assertEquals(documentId, row[UploadTable.document])
            assertEquals(filename, row[UploadTable.originalFilename])
        }
    }

    @Test
    fun `test getUploadsByDocumentId returns correct uploads`() {
        val documentId = createMockDocument(documentRepository)
        val uploadId1 = transaction { uploadRepository.create(documentId, "file1.txt") }
        val uploadId2 = transaction { uploadRepository.create(documentId, "file2.txt") }
        // Create an upload for a different document to ensure filtering works.
        val otherDocumentId = createMockDocument(documentRepository)
        transaction { uploadRepository.create(otherDocumentId, "otherfile.txt") }

        val uploads = transaction { uploadRepository.getUploadsByDocumentId(documentId) }
        assertTrue(uploads.contains(uploadId1))
        assertTrue(uploads.contains(uploadId2))
        assertEquals(2, uploads.size)
    }

    @Test
    fun `test getDocumentIdFromUploadId returns correct document id`() {
        val documentId = createMockDocument(documentRepository)
        val uploadId = transaction { uploadRepository.create(documentId, "file.txt") }
        // Retrieve the document id using the upload id.
        val retrievedDocumentId = transaction { uploadRepository.getDocumentIdFromUploadId(uploadId.value) }
        assertEquals(documentId, retrievedDocumentId)
    }

    @Test
    fun `test notifyChange calls DocumentRepository with correct document id`() {
        val documentId = createMockDocument(documentRepository)
        val uploadId = transaction { uploadRepository.create(documentId, "notify.txt") }

        mockkObject(DocumentChangeNotifier)
        coEvery { DocumentChangeNotifier.notifyChange(any()) } returns Unit

        runBlocking { newSuspendedTransaction { uploadRepository.notifyChange(uploadId.value) } }

        coVerify { DocumentChangeNotifier.notifyChange(documentId.value) }
        unmockkObject(DocumentChangeNotifier)
    }
}
