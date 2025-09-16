package no.nav.sosialhjelp.upload.database.reactive

import io.ktor.server.application.*
import java.util.*

/**
 * Factory class for creating [DocumentStatusChannel] instances.
 *
 * @property environment The application environment used to create the database connection.
 */
class DocumentStatusChannelFactory(
    environment: ApplicationEnvironment,
) {
    private val dbFactory = ReactivePgConnectionFactory(environment)

    /**
     * Creates a [DocumentStatusChannel] for the given document ID.
     *
     * @param documentId The ID of the document to create the channel for.
     * @return A [DocumentStatusChannel] for the given document ID.
     */
    fun create(documentId: UUID): DocumentStatusChannel = DocumentStatusChannel(documentId, dbFactory.createConnection())
}
