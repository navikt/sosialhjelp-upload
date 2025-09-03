package no.nav.sosialhjelp.database

import no.nav.sosialhjelp.common.SoknadDocumentIdent
import no.nav.sosialhjelp.database.schema.DocumentTable
import no.nav.sosialhjelp.testutils.PostgresTestContainer
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DocumentRepositoryTest {
    private val repository = DocumentRepository()

    @BeforeAll
    fun setup() {
        PostgresTestContainer.connectAndStart()
    }

    @BeforeEach
    fun cleanup() {
        transaction { DocumentTable.deleteAll() }
    }

    private fun newIdent(
        soknadId: UUID = UUID.randomUUID(),
        vedleggType: String = "SKANNET_DOKUMENT",
    ) = SoknadDocumentIdent(soknadId = soknadId, vedleggType = vedleggType)

    @Test
    fun `creates a new document if none exists`() {
        val ident = newIdent()
        val owner = "12345678910"

        val documentId = transaction { repository.getOrCreateDocument(ident, owner) }

        transaction {
            val found = DocumentTable.selectAll().single()
            assertEquals(documentId, found[DocumentTable.id])
            assertEquals(owner, found[DocumentTable.ownerIdent])
            assertEquals(ident.soknadId, found[DocumentTable.soknadId])
            assertEquals(ident.vedleggType, found[DocumentTable.vedleggType])
        }
    }

    @Test
    fun `returns existing document for same ident and same user`() {
        val ident = newIdent()
        val owner = "12345678910"

        val first = transaction { repository.getOrCreateDocument(ident, owner) }
        val second = transaction { repository.getOrCreateDocument(ident, owner) }

        assertEquals(first, second)
    }

    @Test
    fun `throws exception when document is owned by another user`() {
        val ident = newIdent()
        val owner1 = "user1"
        val owner2 = "user2"

        transaction {
            repository.getOrCreateDocument(ident, owner1)
        }

        val ex =
            assertThrows<DocumentRepository.DocumentOwnedByAnotherUserException> {
                transaction {
                    repository.getOrCreateDocument(ident, owner2)
                }
            }

        assertEquals(DocumentRepository.DocumentOwnedByAnotherUserException::class, ex::class)
    }
}
