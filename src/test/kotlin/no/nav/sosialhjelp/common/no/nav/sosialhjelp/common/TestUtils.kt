package no.nav.sosialhjelp.common

import DocumentRepository
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

object TestUtils {
    fun createMockDocument(documentRepository: DocumentRepository) =
        transaction {
            documentRepository.getOrCreateDocument(DocumentIdent(UUID.randomUUID(), "bar"), "12345678901")
        }
}
