package no.nav.sosialhjelp.database.reactive

import io.ktor.server.application.*
import java.util.*

class DocumentStatusChannelFactory(
    environment: ApplicationEnvironment,
) {
    private val dbFactory = ReactivePgConnectionFactory(environment)

    fun create(documentId: UUID): DocumentStatusChannel = DocumentStatusChannel(documentId, dbFactory.createConnection())
}
