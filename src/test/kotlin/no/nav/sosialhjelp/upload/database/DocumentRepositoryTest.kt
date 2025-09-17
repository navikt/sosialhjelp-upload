package no.nav.sosialhjelp.upload.database

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import no.nav.sosialhjelp.upload.database.generated.tables.references.DOCUMENT
import no.nav.sosialhjelp.upload.testutils.PostgresTestContainer
import org.jooq.DSLContext
import org.jooq.kotlin.coroutines.transactionCoroutine
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRepositoryTest {
    private lateinit var repository: DocumentRepository
    private lateinit var dsl: DSLContext

    @BeforeAll
    fun setup() {
        dsl = PostgresTestContainer.connectAndStart()
        repository = DocumentRepository(dsl)
    }

    @BeforeEach
    fun cleanup() = runTest {
        dsl.transactionCoroutine { it.dsl().deleteFrom(DOCUMENT).awaitSingle() }
    }

    @Test
    fun `creates a new document if none exists`() = runTest {
        val owner = "12345678910"

        val documentId = dsl.transactionCoroutine { repository.getOrCreateDocument(it, "whatever", owner) }

        dsl.transactionCoroutine {
            val found = it.dsl().selectFrom(DOCUMENT).where(DOCUMENT.ID.eq(documentId)).awaitSingle()
            assertEquals(documentId, found[DOCUMENT.ID])
            assertEquals(owner, found[DOCUMENT.OWNER_IDENT])
            assertEquals("whatever", found[DOCUMENT.EXTERNAL_ID])
        }
    }

    @Test
    fun `returns existing document for same external id and same user`() = runTest {
        val owner = "12345678910"

        val first = dsl.transactionCoroutine { repository.getOrCreateDocument(it, "whatever", owner) }
        val second = dsl.transactionCoroutine { repository.getOrCreateDocument(it, "whatever", owner) }

        assertEquals(first, second)
    }

    @Test
    fun `throws exception when document is owned by another user`() = runTest {
        val owner1 = "user1"
        val owner2 = "user2"

        dsl.transactionCoroutine {
            repository.getOrCreateDocument(it, "whatever", owner1)
        }

        val ex =
            assertThrows<DocumentRepository.DocumentOwnedByAnotherUserException> {
                dsl.transactionCoroutine {
                    repository.getOrCreateDocument(it, "whatever", owner2)
                }
            }

        assertEquals(DocumentRepository.DocumentOwnedByAnotherUserException::class, ex::class)
    }
}
