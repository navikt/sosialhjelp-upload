package no.nav.sosialhjelp.common

import no.nav.sosialhjelp.database.DocumentRepository
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import java.util.UUID

object TestUtils {
    suspend fun createMockDocument(documentRepository: DocumentRepository) =
        suspendTransaction {
            documentRepository.getOrCreateDocument(SoknadDocumentIdent(UUID.randomUUID()), "12345678901")
        }
}
