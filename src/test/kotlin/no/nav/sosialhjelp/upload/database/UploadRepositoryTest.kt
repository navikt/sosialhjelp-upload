package no.nav.sosialhjelp.upload.database

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.common.TestUtils
import no.nav.sosialhjelp.upload.database.generated.tables.Upload.Companion.UPLOAD
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
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
    private lateinit var dsl: DSLContext

    @BeforeAll
    fun setupDatabase() {
        dsl = PostgresTestContainer.connectAndStart()
        documentRepository = DocumentRepository(dsl)
        uploadRepository = UploadRepository()
    }

    @BeforeEach
    fun cleanup(): Unit =
        runBlocking {
            dsl.transactionCoroutine(Dispatchers.IO) {
                it.dsl().deleteFrom(DOCUMENT).awaitSingle()
            }
        }

    @Test
    fun `test create upload`() =
        runTest {
            val documentId = TestUtils.createMockDocument(dsl)
            val filename = "testfile.txt"
            val uploadId = dsl.transactionCoroutine { uploadRepository.create(it, documentId, filename) }

            dsl.transactionCoroutine {
                val upload =
                    it
                        .dsl()
                        .select(
                            UPLOAD.DOCUMENT_ID,
                            UPLOAD.ORIGINAL_FILENAME,
                        ).from(UPLOAD)
                        .where(UPLOAD.ID.eq(uploadId!!))
                        .awaitSingle()
                assertEquals(documentId, upload[UPLOAD.DOCUMENT_ID])
                assertEquals(filename, upload[UPLOAD.ORIGINAL_FILENAME])
            }
        }

    @Test
    fun `test getUploadsByDocumentId returns correct uploads`() =
        runTest {
            val documentId = TestUtils.createMockDocument(dsl)
            val uploadId1 = dsl.transactionCoroutine { uploadRepository.create(it, documentId, "file1.txt") }
            val uploadId2 = dsl.transactionCoroutine { uploadRepository.create(it, documentId, "file2.txt") }
            // Create an upload for a different document to ensure filtering works.
            val otherDocumentId = TestUtils.createMockDocument(dsl)
            dsl.transactionCoroutine { uploadRepository.create(it, otherDocumentId, "otherfile.txt") }

            val uploads =
                dsl
                    .transactionCoroutine { tx ->
                        uploadRepository.getUploadsWithFilenames(tx, documentId).toList()
                    }.map { it.id }
            assertTrue(uploads.contains(uploadId1))
            assertTrue(uploads.contains(uploadId2))
            assertEquals(2, uploads.size)
        }

    @Test
    fun `test getDocumentIdFromUploadId returns correct document id`() =
        runTest {
            val documentId = TestUtils.createMockDocument(dsl)
            val uploadId = dsl.transactionCoroutine { uploadRepository.create(it, documentId, "file.txt") } ?: error("Ingen uploadId")
            // Retrieve the document id using the upload id.
            val retrievedDocumentId = dsl.transactionCoroutine { uploadRepository.getDocumentIdFromUploadId(it, uploadId) }
            assertEquals(documentId, retrievedDocumentId)
        }

    @Test
    fun `test notifyChange calls DocumentRepository with correct document id`() =
        runTest {
            val documentId = TestUtils.createMockDocument(dsl)
            val uploadId = dsl.transactionCoroutine { uploadRepository.create(it, documentId, "notify.txt") } ?: error("Ingen uploadId")

            mockkObject(DocumentChangeNotifier)
            coEvery { DocumentChangeNotifier.notifyChange(any()) } returns Unit

            dsl.transactionCoroutine { uploadRepository.notifyChange(it, uploadId) }

            coVerify { DocumentChangeNotifier.notifyChange(documentId) }
            unmockkObject(DocumentChangeNotifier)
        }
}
